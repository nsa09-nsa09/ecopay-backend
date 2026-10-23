package kz.hrms.splitupauth.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kz.hrms.splitupauth.dto.PayoutBalanceDto;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.Payout;
import kz.hrms.splitupauth.entity.PayoutBatch;
import kz.hrms.splitupauth.entity.PayoutMethod;
import kz.hrms.splitupauth.entity.PayoutStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.payment.gateway.GatewayPayoutRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayPayoutResponse;
import kz.hrms.splitupauth.payment.gateway.GatewayStatusResponse;
import kz.hrms.splitupauth.payment.gateway.PaymentGateway;
import kz.hrms.splitupauth.payment.gateway.PaymentGatewayRegistry;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway;
import kz.hrms.splitupauth.repository.PayoutBatchRepository;
import kz.hrms.splitupauth.repository.PayoutMethodRepository;
import kz.hrms.splitupauth.repository.PayoutRepository;
import kz.hrms.splitupauth.repository.SavedCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class PayoutService {

  private static final int MAX_RETRY = 3;
  private static final int DISPATCH_LEASE_MINUTES = 5;
  private static final int PROVIDER_RECONCILIATION_DELAY_MINUTES = 5;

  /**
   * A submission whose outcome was ambiguous (timeout, unreadable or unsigned response) and that
   * the provider still does not know after this long is handed to manual review — never re-sent.
   */
  private static final int NOT_FOUND_REVIEW_AFTER_MINUTES = 30;

  private final PayoutRepository payoutRepository;
  private final PayoutBatchRepository payoutBatchRepository;
  private final PayoutMethodRepository payoutMethodRepository;
  private final PaymentGatewayRegistry gatewayRegistry;
  private final PaymentEventLogger eventLogger;
  private final SavedCardRepository savedCardRepository;
  private final NotificationService notificationService;
  private final MoneyLedgerService moneyLedgerService;
  private final PayoutEligibilityService payoutEligibilityService;
  private final PayoutBalanceService payoutBalanceService;
  private final Clock clock;
  private final PlatformTransactionManager transactionManager;

  private static final int BALANCE_HOLD_RETRY_MINUTES = 30;
  private static final int PENDING_PROVIDER_REVIEW_AFTER_HOURS = 24;

  /**
   * EcoPay owner payout reserve: days the owner's share of a CAPTURED member payment stays held
   * before it may be paid out ({@code releaseAt = capturedAt + holdDays}). This is EcoPay's
   * internal settlement rule and has nothing to do with card authorization/capture at the provider
   * (members are charged one-step, see {@code ecopay.payments.freedompay.auto-clearing}).
   */
  @Value("${app.payout.hold-days:30}")
  private int payoutHoldDays;

  /**
   * Test-only override: when positive, the hold is this many minutes and {@code hold-days} is
   * ignored. Rejected in prod by ProductionStartupGuard.
   */
  @Value("${app.payout.hold-minutes:0}")
  private int payoutHoldMinutes;

  @Value("${app.payout.batch-coalesce-hours:24}")
  private int payoutBatchCoalesceHours;

  /**
   * Called from PaymentService when a member's charge succeeds. Creates a pending payout for the
   * room owner.
   */
  @Transactional
  public Payout createOwnerPayoutForSuccessfulPayment(PaymentIntent intent) {
    if (intent.getRoomMember() == null || intent.getRoomMember().getRoom() == null) {
      return null;
    }
    Payout existing = payoutRepository.findByTriggeringPaymentIntent(intent).orElse(null);
    if (existing != null) {
      return existing;
    }
    User owner = intent.getRoomMember().getRoom().getOwner();
    // The member was charged (share + commission). The owner is paid the full share;
    // EcoPay keeps the commission. Owners never pay a commission themselves.
    BigDecimal commission =
        intent.getCommissionAmount() == null ? BigDecimal.ZERO : intent.getCommissionAmount();
    BigDecimal payoutAmount =
        intent.getAmount().subtract(commission).setScale(2, RoundingMode.HALF_UP);
    if (payoutAmount.signum() <= 0) {
      throw new InvalidRequestException("Payout amount must be greater than zero");
    }

    // Hold the payout: capture happened now, but the owner is only paid once the hold
    // window elapses. The dispatcher skips payouts until releaseAt is reached.
    LocalDateTime now = LocalDateTime.now(clock);
    LocalDateTime capturedAt = intent.getCapturedAt() == null ? now : intent.getCapturedAt();
    LocalDateTime releaseAt =
        payoutHoldMinutes > 0
            ? capturedAt.plusMinutes(payoutHoldMinutes)
            : capturedAt.plusDays(payoutHoldDays);

    Payout payout =
        Payout.builder()
            .user(owner)
            .room(intent.getRoomMember().getRoom())
            .triggeringPaymentIntent(intent)
            .amount(payoutAmount)
            .originalAmount(payoutAmount)
            .refundedShareAmount(BigDecimal.ZERO.setScale(2))
            .payableAmount(payoutAmount)
            .currency("KZT")
            .status(PayoutStatus.PENDING)
            .releaseAt(releaseAt)
            .capturedAt(capturedAt)
            .idempotencyKey("payout-intent-" + intent.getId())
            .build();
    payout = payoutRepository.save(payout);

    moneyLedgerService.append(
        "OWNER_HOLD",
        payoutAmount,
        "KZT",
        "CREDIT",
        intent,
        null,
        null,
        payout,
        owner,
        "owner-hold-intent-" + intent.getId());

    eventLogger.log(
        "PAYOUT",
        payout.getId(),
        "CREATED",
        null,
        payout.getStatus().name(),
        null,
        null,
        payout.getIdempotencyKey(),
        java.util.Map.of(
            "amount",
            payoutAmount.toPlainString(),
            "commission",
            commission.toPlainString(),
            "releaseAt",
            releaseAt.toString(),
            "holdDays",
            String.valueOf(payoutHoldDays),
            "holdMinutes",
            String.valueOf(payoutHoldMinutes)));

    return payout;
  }

  /**
   * Run every minute: pick up payouts whose hold window has elapsed (releaseAt &lt;= now) and try
   * to dispatch them. Held payouts (releaseAt in the future) are skipped until due.
   */
  public void processPendingPayouts() {
    LocalDateTime now = LocalDateTime.now(clock);
    for (PayoutBatch batch : payoutBatchRepository.findRetryableForDispatch(now)) {
      try {
        dispatchPayoutBatch(batch.getId());
      } catch (Exception ex) {
        log.error("Payout batch {} dispatch retry failed: {}", batch.getId(), ex.getMessage());
      }
    }

    List<Payout> pending =
        payoutRepository.findDispatchable(
            List.of(PayoutStatus.PENDING, PayoutStatus.PENDING_METHOD, PayoutStatus.ON_HOLD), now);
    Map<BatchGroupKey, List<Payout>> grouped = new LinkedHashMap<>();
    for (Payout payout : pending) {
      if (payout.getStatus() == PayoutStatus.PROCESSING) {
        try {
          dispatchPayout(payout.getId());
        } catch (Exception ex) {
          log.error("Payout {} dispatch failed: {}", payout.getId(), ex.getMessage());
        }
        continue;
      }
      BatchCandidate candidate = prepareBatchCandidate(payout.getId());
      if (candidate == null) {
        continue;
      }
      grouped.computeIfAbsent(candidate.groupKey(), key -> new ArrayList<>()).add(payout);
    }

    // B1: send only as much as the merchant payout balance (minus reserve) covers; the rest wait in
    // ON_HOLD. An unknown balance (null) never gates — a provider hiccup can't freeze every payout.
    BigDecimal available = payoutBalanceService.availableForDispatch();
    BigDecimal spent = BigDecimal.ZERO;
    boolean heldForBalance = false;
    for (List<Payout> group : grouped.values()) {
      BigDecimal groupAmount =
          group.stream()
              .map(this::payoutPayableAmount)
              .reduce(BigDecimal.ZERO, BigDecimal::add)
              .setScale(2, RoundingMode.HALF_UP);
      if (available != null && spent.add(groupAmount).compareTo(available) > 0) {
        holdGroupForBalance(group.stream().map(Payout::getId).toList(), now);
        heldForBalance = true;
        continue;
      }
      try {
        if (group.size() > 1) {
          dispatchNewPayoutBatch(group.stream().map(Payout::getId).toList());
        } else {
          Payout payout = group.get(0);
          if (!shouldWaitForCoalescing(payout, now)) {
            dispatchPayout(payout.getId());
          }
        }
        spent = spent.add(groupAmount);
      } catch (Exception ex) {
        log.error("Payout group dispatch failed: {}", ex.getMessage());
      }
    }
    if (heldForBalance) {
      payoutBalanceService.alertBalanceLow();
    }
  }

  /**
   * B1: park a group of due payouts in ON_HOLD because the merchant balance will not cover them
   * this tick. They are retried after {@value #BALANCE_HOLD_RETRY_MINUTES} minutes (or the next
   * top-up). The owner is never told — the delay is not their fault; their payout stays "queued"
   * internally.
   */
  private void holdGroupForBalance(List<Long> payoutIds, LocalDateTime now) {
    tx().executeWithoutResult(
            status -> {
              for (Long id : payoutIds) {
                Payout payout = payoutRepository.findWithLockById(id).orElse(null);
                if (payout == null
                    || payout.getPayoutBatch() != null
                    || (payout.getStatus() != PayoutStatus.PENDING
                        && payout.getStatus() != PayoutStatus.PENDING_METHOD
                        && payout.getStatus() != PayoutStatus.ON_HOLD)) {
                  continue;
                }
                payout.setStatus(PayoutStatus.ON_HOLD);
                payout.setFailureReason("MERCHANT_BALANCE_LOW");
                payout.setLeaseUntil(null);
                payout.setNextRetryAt(now.plusMinutes(BALANCE_HOLD_RETRY_MINUTES));
                payoutRepository.save(payout);
                eventLogger.log(
                    "PAYOUT",
                    payout.getId(),
                    "BALANCE_HOLD",
                    null,
                    PayoutStatus.ON_HOLD.name(),
                    null,
                    null,
                    payout.getIdempotencyKey(),
                    java.util.Map.of("reason", "MERCHANT_BALANCE_LOW"));
              }
            });
  }

  /**
   * Polls the provider for payouts whose initial response was non-final. A status check never
   * resends money: only an explicit SUCCESS settles the ledger, while an explicit failure is held
   * for review.
   */
  public void reconcilePendingProviderPayouts() {
    List<Payout> pending =
        payoutRepository.findProviderPendingForReconciliation(LocalDateTime.now(clock));
    for (Payout payout : pending) {
      if (payout.getProviderOrderId() == null && payout.getProviderPayoutId() == null) {
        continue;
      }
      try {
        PaymentGateway gateway = gatewayRegistry.defaultGateway();
        GatewayStatusResponse providerStatus =
            gateway.getPayoutStatus(payout.getProviderPayoutId(), payoutProviderOrderId(payout));
        tx().executeWithoutResult(
                status -> completePayoutReconciliation(payout.getId(), providerStatus));
      } catch (Exception ex) {
        log.warn("Payout {} status reconciliation failed: {}", payout.getId(), ex.getMessage());
        tx().executeWithoutResult(
                status -> deferPayoutReconciliation(payout.getId(), ex.getMessage()));
      }
    }
    List<PayoutBatch> pendingBatches =
        payoutBatchRepository.findProviderPendingForReconciliation(LocalDateTime.now(clock));
    for (PayoutBatch batch : pendingBatches) {
      if (batch.getProviderOrderId() == null && batch.getProviderPayoutId() == null) {
        continue;
      }
      try {
        PaymentGateway gateway = gatewayRegistry.defaultGateway();
        GatewayStatusResponse providerStatus =
            gateway.getPayoutStatus(batch.getProviderPayoutId(), batch.getProviderOrderId());
        tx().executeWithoutResult(
                status -> completeBatchReconciliation(batch.getId(), providerStatus));
      } catch (Exception ex) {
        log.warn(
            "Payout batch {} status reconciliation failed: {}", batch.getId(), ex.getMessage());
        tx().executeWithoutResult(
                status -> deferBatchReconciliation(batch.getId(), ex.getMessage()));
      }
    }
  }

  public void dispatchPayout(Long payoutId) {
    DispatchClaim claim = tx().execute(status -> claimPayoutForDispatch(payoutId));
    if (claim == null) {
      return;
    }
    if (claim.previousOrderId() != null) {
      boolean alreadyAtProvider = preDispatchStatusCheck(claim.payoutId(), claim.previousOrderId());
      if (alreadyAtProvider) {
        return;
      }
    }
    GatewayPayoutResponse resp;
    String providerName;
    try {
      PaymentGateway gateway = gatewayRegistry.defaultGateway();
      providerName = gateway.providerName();
      resp =
          gateway.payout(
              GatewayPayoutRequest.builder()
                  .payoutId(claim.payoutId())
                  .providerOrderId(claim.providerOrderId())
                  .idempotencyKey(claim.idempotencyKey())
                  .destinationUserId(claim.destinationUserId())
                  .destinationCardToken(claim.destinationCardToken())
                  .amount(claim.amount())
                  .currency(claim.currency())
                  .description(claim.description())
                  .build());
    } catch (Exception ex) {
      tx().executeWithoutResult(status -> markPayoutDispatchException(claim.payoutId(), ex));
      return;
    }
    String finalProviderName = providerName;
    tx().executeWithoutResult(
            status -> completePayoutDispatch(claim.payoutId(), resp, finalProviderName));
  }

  /**
   * Before retrying a payout that was previously submitted, check its status at the provider.
   * Returns true if the payout already exists at the provider (PENDING_PROVIDER), false if not
   * found (safe to re-send with a new order id).
   */
  private boolean preDispatchStatusCheck(Long payoutId, String previousOrderId) {
    try {
      PaymentGateway gateway = gatewayRegistry.defaultGateway();
      GatewayStatusResponse status = gateway.getPayoutStatus(null, previousOrderId);
      if (status.isNotFound()) {
        return false;
      }
      String mapped = status.getStatus();
      if ("SUCCESS".equals(mapped) || "PENDING".equals(mapped)) {
        tx().executeWithoutResult(
                s -> {
                  Payout payout = payoutRepository.findWithLockById(payoutId).orElse(null);
                  if (payout == null) return;
                  payout.setStatus(PayoutStatus.PENDING_PROVIDER);
                  payout.setProviderOrderId(previousOrderId);
                  if (status.getExternalPaymentId() != null) {
                    payout.setProviderPayoutId(status.getExternalPaymentId());
                  }
                  payout.setLeaseUntil(null);
                  payout.setNextRetryAt(
                      LocalDateTime.now(clock).plusMinutes(PROVIDER_RECONCILIATION_DELAY_MINUTES));
                  payoutRepository.save(payout);
                });
        log.info(
            "Payout {} pre-dispatch check: previous order {} is {} at provider, skipping re-send",
            payoutId,
            previousOrderId,
            mapped);
        return true;
      }
      return false;
    } catch (Exception ex) {
      log.warn(
          "Payout {} pre-dispatch status check failed: {}, proceeding with new order id",
          payoutId,
          ex.getMessage());
      return false;
    }
  }

  private BatchCandidate prepareBatchCandidate(Long payoutId) {
    return tx().execute(
            status -> {
              Payout payout = payoutRepository.findWithLockById(payoutId).orElse(null);
              if (payout == null || payout.getPayoutBatch() != null) {
                return null;
              }
              if (payout.getStatus() != PayoutStatus.PENDING
                  && payout.getStatus() != PayoutStatus.PENDING_METHOD
                  && payout.getStatus() != PayoutStatus.ON_HOLD) {
                return null;
              }
              LocalDateTime now = LocalDateTime.now(clock);
              if ((payout.getReleaseAt() != null && payout.getReleaseAt().isAfter(now))
                  || (payout.getNextRetryAt() != null && payout.getNextRetryAt().isAfter(now))) {
                return null;
              }
              PayoutEligibilityService.Decision eligibility =
                  payoutEligibilityService.evaluate(payout);
              if (!eligibility.eligible()) {
                payout.setFailureReason("Eligibility blocked: " + eligibility.reason());
                payout.setNextRetryAt(now.plusMinutes(eligibility.temporary() ? 60 : 360));
                payoutRepository.save(payout);
                eventLogger.log(
                    "PAYOUT",
                    payout.getId(),
                    "ELIGIBILITY_BLOCKED",
                    payout.getStatus().name(),
                    payout.getStatus().name(),
                    null,
                    null,
                    payout.getIdempotencyKey(),
                    java.util.Map.of("reason", eligibility.reason()));
                return null;
              }
              PayoutMethod method = dispatchableMethod(payout.getUser());
              if (method == null) {
                payout.setStatus(PayoutStatus.PENDING_METHOD);
                payoutRepository.save(payout);
                return null;
              }
              if (payout.getRetryCount() != null && payout.getRetryCount() >= MAX_RETRY) {
                payout.setStatus(PayoutStatus.FAILED);
                payout.setFailureReason("Max retries exceeded");
                payoutRepository.save(payout);
                return null;
              }
              BigDecimal payable = payoutPayableAmount(payout);
              if (payable.signum() <= 0) {
                payout.setStatus(PayoutStatus.REVERSED);
                payout.setFailureReason("Reversed: payable amount is zero");
                payout.setProcessedAt(now);
                payoutRepository.save(payout);
                return null;
              }
              return new BatchCandidate(
                  payout.getId(),
                  new BatchGroupKey(
                      payout.getUser().getId(), method.getId(), payout.getCurrency()));
            });
  }

  private boolean shouldWaitForCoalescing(Payout payout, LocalDateTime now) {
    if (payoutBatchCoalesceHours <= 0 || payout.getReleaseAt() == null) {
      return false;
    }
    return payout.getReleaseAt().plusHours(payoutBatchCoalesceHours).isAfter(now);
  }

  private void dispatchNewPayoutBatch(List<Long> payoutIds) {
    BatchDispatchClaim claim = tx().execute(status -> claimNewPayoutBatchForDispatch(payoutIds));
    dispatchPayoutBatchClaim(claim);
  }

  private void dispatchPayoutBatch(Long batchId) {
    BatchDispatchClaim claim = tx().execute(status -> claimExistingPayoutBatchForDispatch(batchId));
    dispatchPayoutBatchClaim(claim);
  }

  private void dispatchPayoutBatchClaim(BatchDispatchClaim claim) {
    if (claim == null) {
      return;
    }
    GatewayPayoutResponse resp;
    String providerName;
    try {
      PaymentGateway gateway = gatewayRegistry.defaultGateway();
      providerName = gateway.providerName();
      resp =
          gateway.payout(
              GatewayPayoutRequest.builder()
                  .payoutId(claim.batchId())
                  .providerOrderId(claim.providerOrderId())
                  .idempotencyKey(claim.idempotencyKey())
                  .destinationUserId(claim.destinationUserId())
                  .destinationCardToken(claim.destinationCardToken())
                  .amount(claim.amount())
                  .currency(claim.currency())
                  .description("Выплата EcoPay (пакет #" + claim.batchId() + ")")
                  .build());
    } catch (Exception ex) {
      tx().executeWithoutResult(status -> markBatchDispatchException(claim.batchId(), ex));
      return;
    }
    String finalProviderName = providerName;
    tx().executeWithoutResult(
            status -> completeBatchDispatch(claim.batchId(), resp, finalProviderName));
  }

  private BatchDispatchClaim claimNewPayoutBatchForDispatch(List<Long> payoutIds) {
    if (payoutIds == null || payoutIds.size() < 2) {
      return null;
    }
    List<Long> ids = payoutIds.stream().distinct().sorted().toList();
    List<Payout> payouts = payoutRepository.findWithLockByIdIn(ids);
    if (payouts.size() != ids.size()) {
      return null;
    }
    LocalDateTime now = LocalDateTime.now(clock);
    Payout first = payouts.get(0);
    PayoutMethod method = dispatchableMethod(first.getUser());
    if (method == null) {
      return null;
    }
    BigDecimal total = BigDecimal.ZERO;
    for (Payout payout : payouts) {
      if (payout.getPayoutBatch() != null
          || (payout.getStatus() != PayoutStatus.PENDING
              && payout.getStatus() != PayoutStatus.PENDING_METHOD)
          || (payout.getReleaseAt() != null && payout.getReleaseAt().isAfter(now))
          || (payout.getNextRetryAt() != null && payout.getNextRetryAt().isAfter(now))
          || !payout.getUser().getId().equals(first.getUser().getId())
          || !payout.getCurrency().equals(first.getCurrency())) {
        return null;
      }
      PayoutEligibilityService.Decision eligibility = payoutEligibilityService.evaluate(payout);
      if (!eligibility.eligible()) {
        return null;
      }
      PayoutMethod currentMethod = dispatchableMethod(payout.getUser());
      if (currentMethod == null || !currentMethod.getId().equals(method.getId())) {
        return null;
      }
      total = total.add(payoutPayableAmount(payout));
      if (payoutPayableAmount(payout).signum() <= 0) return null;
    }
    total = total.setScale(2, RoundingMode.HALF_UP);
    if (total.signum() <= 0) {
      return null;
    }
    PayoutBatch batch =
        payoutBatchRepository.save(
            PayoutBatch.builder()
                .owner(first.getUser())
                .payoutMethod(method)
                .currency(first.getCurrency())
                .amount(total)
                .status("PROCESSING")
                .idempotencyKey(batchIdempotencyKey(ids))
                .destinationCardToken(method.getProviderCardToken())
                .providerName(gatewayRegistry.defaultGateway().providerName())
                .submissionStartedAt(now)
                .leaseUntil(now.plusMinutes(DISPATCH_LEASE_MINUTES))
                .build());
    batch.setProviderOrderId("ecopay-batch-" + batch.getId());
    for (Payout payout : payouts) {
      payout.setPayoutBatch(batch);
      payout.setPayoutMethod(method);
      payout.setStatus(PayoutStatus.PROCESSING);
      payout.setLeaseUntil(now.plusMinutes(DISPATCH_LEASE_MINUTES));
      payout.setAmount(payoutPayableAmount(payout));
      payout.setSubmittedAmount(payoutPayableAmount(payout));
      payout.setSubmittedAt(now);
    }
    payoutRepository.saveAll(payouts);
    return new BatchDispatchClaim(
        batch.getId(),
        batch.getIdempotencyKey(),
        payoutUserId(first.getUser()),
        method.getProviderCardToken(),
        total,
        first.getCurrency(),
        batch.getProviderOrderId());
  }

  private BatchDispatchClaim claimExistingPayoutBatchForDispatch(Long batchId) {
    PayoutBatch batch = payoutBatchRepository.findWithLockById(batchId).orElse(null);
    if (batch == null || !"PROCESSING".equals(batch.getStatus())) {
      return null;
    }
    LocalDateTime now = LocalDateTime.now(clock);
    if (batch.getProviderPayoutId() != null
        || (batch.getNextRetryAt() != null && batch.getNextRetryAt().isAfter(now))
        || (batch.getLeaseUntil() != null && batch.getLeaseUntil().isAfter(now))) {
      return null;
    }
    if (batch.getRetryCount() != null && batch.getRetryCount() >= MAX_RETRY) {
      batch.setStatus("FAILED");
      batch.setFailureReason("Max retries exceeded");
      payoutBatchRepository.save(batch);
      markBatchChildrenTerminal(batch.getId(), PayoutStatus.FAILED, "Max retries exceeded");
      return null;
    }
    PaymentGateway gateway = gatewayRegistry.defaultGateway();
    if (!gateway.supportsIdempotentPayoutReplay()
        || !gateway.providerName().equals(batch.getProviderName())) {
      batch.setStatus("REQUIRES_REVIEW");
      batch.setFailureReason("Submission may have succeeded; automatic replay is not safe");
      markBatchChildrenTerminal(batchId, PayoutStatus.REQUIRES_REVIEW, batch.getFailureReason());
      return null;
    }
    verifyBatchSnapshot(batch);
    batch.setLeaseUntil(now.plusMinutes(DISPATCH_LEASE_MINUTES));
    payoutBatchRepository.save(batch);
    return new BatchDispatchClaim(
        batch.getId(),
        batch.getIdempotencyKey(),
        payoutUserId(batch.getOwner()),
        batch.getDestinationCardToken(),
        batch.getAmount(),
        batch.getCurrency(),
        batch.getProviderOrderId());
  }

  private DispatchClaim claimPayoutForDispatch(Long payoutId) {
    Payout payout = payoutRepository.findWithLockById(payoutId).orElse(null);
    if (payout == null || payout.getPayoutBatch() != null) return null;
    LocalDateTime now = LocalDateTime.now(clock);
    boolean staleProcessing =
        payout.getStatus() == PayoutStatus.PROCESSING
            && payout.getProviderPayoutId() == null
            && payout.getLeaseUntil() != null
            && !payout.getLeaseUntil().isAfter(now);
    if (!staleProcessing
        && payout.getStatus() != PayoutStatus.PENDING
        && payout.getStatus() != PayoutStatus.PENDING_METHOD
        && payout.getStatus() != PayoutStatus.ON_HOLD) {
      return null;
    }
    if (!staleProcessing
        && payout.getNextRetryAt() != null
        && payout.getNextRetryAt().isAfter(now)) {
      return null;
    }
    PayoutEligibilityService.Decision eligibility = payoutEligibilityService.evaluate(payout);
    if (!eligibility.eligible()) {
      payout.setFailureReason("Eligibility blocked: " + eligibility.reason());
      payout.setNextRetryAt(now.plusMinutes(eligibility.temporary() ? 60 : 360));
      payoutRepository.save(payout);
      eventLogger.log(
          "PAYOUT",
          payout.getId(),
          "ELIGIBILITY_BLOCKED",
          payout.getStatus().name(),
          payout.getStatus().name(),
          null,
          null,
          payout.getIdempotencyKey(),
          java.util.Map.of("reason", eligibility.reason()));
      return null;
    }

    PayoutMethod method = dispatchableMethod(payout.getUser());
    if (method == null) {
      payout.setStatus(PayoutStatus.PENDING_METHOD);
      payoutRepository.save(payout);
      return null;
    }

    if (payout.getRetryCount() != null && payout.getRetryCount() >= MAX_RETRY) {
      payout.setStatus(PayoutStatus.FAILED);
      payout.setFailureReason("Max retries exceeded");
      payoutRepository.save(payout);
      return null;
    }

    // If previously submitted, the old order id needs a pre-dispatch status check (done outside
    // this transaction). Generate a new order id for the retry so that the provider sees a unique
    // pg_order_id for each attempt.
    String previousOrderId = null;
    if (payout.getSubmittedAmount() != null && payout.getProviderOrderId() != null) {
      previousOrderId = payout.getProviderOrderId();
    }

    payout.setStatus(PayoutStatus.PROCESSING);
    payout.setLeaseUntil(now.plusMinutes(DISPATCH_LEASE_MINUTES));
    payout.setPayoutMethod(method);
    payout.setSubmittedAmount(payoutPayableAmount(payout));
    payout.setSubmittedAt(now);
    payout.setAmount(payout.getSubmittedAmount());
    int attempt = payout.getRetryCount() == null ? 0 : payout.getRetryCount();
    String newOrderId =
        attempt == 0
            ? "ecopay-payout-" + payout.getId()
            : "ecopay-payout-" + payout.getId() + "-r" + attempt;
    payout.setProviderOrderId(newOrderId);
    payout = payoutRepository.save(payout);

    String userId = payoutUserId(payout.getUser());
    String description = payoutDescription(payout);
    return new DispatchClaim(
        payout.getId(),
        payout.getIdempotencyKey(),
        userId,
        method.getProviderCardToken(),
        payout.getAmount(),
        payout.getCurrency(),
        payout.getProviderOrderId(),
        description,
        previousOrderId);
  }

  private static String payoutUserId(User user) {
    String publicId = user.getPublicId();
    if (publicId != null && !publicId.isBlank()) {
      return "u" + publicId;
    }
    return "u" + user.getId();
  }

  private static String payoutDescription(Payout payout) {
    String roomTitle =
        payout.getRoom() != null && payout.getRoom().getTitle() != null
            ? payout.getRoom().getTitle()
            : null;
    if (roomTitle != null) {
      return "Выплата EcoPay за комнату «" + truncateDescription(roomTitle, 80) + "»";
    }
    return "Выплата EcoPay #" + payout.getId();
  }

  private static String truncateDescription(String value, int max) {
    if (value == null) return "";
    return value.length() <= max ? value : value.substring(0, max) + "…";
  }

  private static final java.util.Set<String> CARD_ERROR_CODES =
      java.util.Set.of(
          "300", "301", "302", "303", "304", "305", "306", "307", "310", "315", "320", "325", "330",
          "340", "350", "360", "365", "370", "380", "400", "405", "410", "415", "420");

  private void completePayoutDispatch(
      Long payoutId, GatewayPayoutResponse resp, String providerName) {
    Payout payout = payoutRepository.findWithLockById(payoutId).orElse(null);
    if (payout == null || payout.getStatus() != PayoutStatus.PROCESSING) {
      return;
    }
    payoutBalanceService.recordReg2regBalance(resp.getProviderBalance());
    if (resp.isSuccess() && !FreedomPayGateway.PROVIDER_NAME.equalsIgnoreCase(providerName)) {
      payout.setStatus(PayoutStatus.SUCCESS);
      payout.setProviderPayoutId(resp.getExternalPayoutId());
      payout.setProcessedAt(LocalDateTime.now(clock));
      payout.setLeaseUntil(null);
      payout.setNextRetryAt(null);
      appendPayoutSuccessLedger(payout);
    } else if (resp.isPending() || resp.isSuccess()) {
      payout.setStatus(PayoutStatus.PENDING_PROVIDER);
      payout.setProviderPayoutId(resp.getExternalPayoutId());
      payout.setLeaseUntil(null);
      payout.setNextRetryAt(
          LocalDateTime.now(clock).plusMinutes(PROVIDER_RECONCILIATION_DELAY_MINUTES));
    } else {
      String fullReason =
          "Provider rejected payout (status="
              + resp.getProviderStatusCode()
              + ", code="
              + resp.getFailureCode()
              + "): "
              + resp.getFailureMessage();
      payout.setFailureReason(fullReason);

      if (isCardError(resp.getFailureCode())) {
        payout.setStatus(PayoutStatus.PENDING_METHOD);
        payout.setLeaseUntil(null);
        payout.setNextRetryAt(null);
        payoutRepository.save(payout);
        notifyPayoutCardInvalid(payout);
        log.warn("Payout {} card error: {}", payoutId, fullReason);
        return;
      }

      payout.setRetryCount((payout.getRetryCount() == null ? 0 : payout.getRetryCount()) + 1);
      if (payout.getRetryCount() >= MAX_RETRY) {
        payout.setStatus(PayoutStatus.REQUIRES_REVIEW);
        payout.setLeaseUntil(null);
        payout.setNextRetryAt(null);
        notifyPayoutRequiresReview(payout);
      } else {
        payout.setStatus(PayoutStatus.PENDING);
        payout.setLeaseUntil(null);
        payout.setNextRetryAt(
            LocalDateTime.now(clock).plusSeconds(retryBackoffSeconds(payout.getRetryCount())));
      }
      log.warn("Payout {} provider dispatch failed: {}", payoutId, fullReason);
    }
    payoutRepository.save(payout);
  }

  private static boolean isCardError(String errorCode) {
    return errorCode != null && CARD_ERROR_CODES.contains(errorCode.trim());
  }

  private void notifyPayoutCardInvalid(Payout payout) {
    if (payout.getUser() == null) return;
    notificationService.notify(
        payout.getUser(),
        kz.hrms.splitupauth.entity.NotificationType.PAYOUT_REQUIRES_REVIEW,
        java.util.Map.of("reason", "CARD_INVALID", "payoutId", String.valueOf(payout.getId())),
        "/payment/payout",
        java.util.Map.of("payoutId", payout.getId()));
  }

  private void completeBatchDispatch(
      Long batchId, GatewayPayoutResponse resp, String providerName) {
    PayoutBatch batch = payoutBatchRepository.findWithLockById(batchId).orElse(null);
    if (batch == null || !"PROCESSING".equals(batch.getStatus())) {
      return;
    }
    payoutBalanceService.recordReg2regBalance(resp.getProviderBalance());
    LocalDateTime now = LocalDateTime.now(clock);
    if (resp.isSuccess() && !FreedomPayGateway.PROVIDER_NAME.equalsIgnoreCase(providerName)) {
      batch.setStatus("SUCCESS");
      batch.setProviderPayoutId(resp.getExternalPayoutId());
      batch.setProcessedAt(now);
      batch.setLeaseUntil(null);
      batch.setNextRetryAt(null);
      payoutBatchRepository.save(batch);
      completeBatchChildren(batch);
      notifyPayoutBatchSent(batch);
    } else if (resp.isPending() || resp.isSuccess()) {
      batch.setStatus("PENDING_PROVIDER");
      batch.setProviderPayoutId(resp.getExternalPayoutId());
      batch.setLeaseUntil(null);
      batch.setNextRetryAt(now.plusMinutes(PROVIDER_RECONCILIATION_DELAY_MINUTES));
      payoutBatchRepository.save(batch);
    } else {
      int retryCount = (batch.getRetryCount() == null ? 0 : batch.getRetryCount()) + 1;
      batch.setRetryCount(retryCount);
      batch.setFailureReason(resp.getFailureMessage());
      batch.setStatus(retryCount >= MAX_RETRY ? "FAILED" : "PROCESSING");
      batch.setLeaseUntil(null);
      batch.setNextRetryAt(
          retryCount >= MAX_RETRY ? null : now.plusSeconds(retryBackoffSeconds(retryCount)));
      payoutBatchRepository.save(batch);
      if (retryCount >= MAX_RETRY) {
        markBatchChildrenTerminal(batch.getId(), PayoutStatus.FAILED, resp.getFailureMessage());
      }
    }
  }

  private void completeBatchReconciliation(Long batchId, GatewayStatusResponse providerStatus) {
    PayoutBatch batch = payoutBatchRepository.findWithLockById(batchId).orElse(null);
    if (batch == null || !"PENDING_PROVIDER".equals(batch.getStatus())) {
      return;
    }
    String status = providerStatus == null ? "PENDING" : providerStatus.getStatus();
    LocalDateTime now = LocalDateTime.now(clock);
    backfillProviderId(batch, providerStatus);
    if (providerStatus != null && providerStatus.isNotFound()) {
      LocalDateTime since =
          batch.getSubmissionStartedAt() == null
              ? batch.getCreatedAt()
              : batch.getSubmissionStartedAt();
      if (since != null && since.plusMinutes(NOT_FOUND_REVIEW_AFTER_MINUTES).isBefore(now)) {
        batch.setStatus("REQUIRES_REVIEW");
        batch.setFailureReason(
            "Provider has no record of this payout order; verify before resending");
        batch.setNextRetryAt(null);
        payoutBatchRepository.save(batch);
        markBatchChildrenTerminal(
            batch.getId(), PayoutStatus.REQUIRES_REVIEW, batch.getFailureReason());
        return;
      }
      batch.setNextRetryAt(now.plusMinutes(PROVIDER_RECONCILIATION_DELAY_MINUTES));
      payoutBatchRepository.save(batch);
      return;
    }
    if ("SUCCESS".equals(status)) {
      batch.setStatus("SUCCESS");
      batch.setFailureReason(null);
      batch.setProcessedAt(now);
      batch.setNextRetryAt(null);
      payoutBatchRepository.save(batch);
      completeBatchChildren(batch);
      notifyPayoutBatchSent(batch);
      return;
    }
    if ("FAILED".equals(status)) {
      batch.setStatus("REQUIRES_REVIEW");
      batch.setFailureReason(
          providerStatus.getFailureMessage() == null
              ? "Provider status reported payout failure"
              : providerStatus.getFailureMessage());
      batch.setProcessedAt(now);
      batch.setNextRetryAt(null);
      payoutBatchRepository.save(batch);
      markBatchChildrenTerminal(
          batch.getId(), PayoutStatus.REQUIRES_REVIEW, batch.getFailureReason());
      return;
    }
    batch.setNextRetryAt(now.plusMinutes(PROVIDER_RECONCILIATION_DELAY_MINUTES));
    payoutBatchRepository.save(batch);
  }

  private void deferBatchReconciliation(Long batchId, String failureMessage) {
    PayoutBatch batch = payoutBatchRepository.findWithLockById(batchId).orElse(null);
    if (batch == null || !"PENDING_PROVIDER".equals(batch.getStatus())) {
      return;
    }
    batch.setFailureReason("Status check failed: " + failureMessage);
    batch.setNextRetryAt(
        LocalDateTime.now(clock).plusMinutes(PROVIDER_RECONCILIATION_DELAY_MINUTES));
    payoutBatchRepository.save(batch);
  }

  private void markBatchDispatchException(Long batchId, Exception ex) {
    PayoutBatch batch = payoutBatchRepository.findWithLockById(batchId).orElse(null);
    if (batch == null || !"PROCESSING".equals(batch.getStatus())) {
      return;
    }
    if (!gatewayRegistry.defaultGateway().supportsIdempotentPayoutReplay()) {
      // The provider may have accepted the transfer. Never resend: reconcile by order id.
      batch.setStatus("PENDING_PROVIDER");
      batch.setFailureReason("Ambiguous provider submission: " + ex.getClass().getSimpleName());
      batch.setLeaseUntil(null);
      batch.setNextRetryAt(
          LocalDateTime.now(clock).plusMinutes(PROVIDER_RECONCILIATION_DELAY_MINUTES));
      payoutBatchRepository.save(batch);
      return;
    }
    int retryCount = (batch.getRetryCount() == null ? 0 : batch.getRetryCount()) + 1;
    batch.setRetryCount(retryCount);
    batch.setFailureReason(ex.getMessage());
    batch.setStatus(retryCount >= MAX_RETRY ? "FAILED" : "PROCESSING");
    batch.setLeaseUntil(null);
    batch.setNextRetryAt(
        retryCount >= MAX_RETRY
            ? null
            : LocalDateTime.now(clock).plusSeconds(retryBackoffSeconds(retryCount)));
    payoutBatchRepository.save(batch);
    if (retryCount >= MAX_RETRY) {
      markBatchChildrenTerminal(batch.getId(), PayoutStatus.FAILED, ex.getMessage());
    }
  }

  private void completeBatchChildren(PayoutBatch batch) {
    for (Payout payout : verifyBatchSnapshot(batch)) {
      if (payout.getStatus() == PayoutStatus.SUCCESS) {
        appendPayoutSuccessLedger(payout);
        continue;
      }
      payout.setStatus(PayoutStatus.SUCCESS);
      payout.setFailureReason(null);
      payout.setProcessedAt(LocalDateTime.now(clock));
      payout.setLeaseUntil(null);
      payout.setNextRetryAt(null);
      payout.setAmount(payout.getSubmittedAmount());
      payoutRepository.save(payout);
      appendPayoutSuccessLedger(payout);
    }
  }

  private void markBatchChildrenTerminal(Long batchId, PayoutStatus status, String failureReason) {
    for (Payout payout : payoutRepository.findWithLockByPayoutBatchId(batchId)) {
      if (payout.getStatus() == PayoutStatus.SUCCESS) {
        continue;
      }
      payout.setStatus(status);
      payout.setFailureReason(failureReason);
      payout.setProcessedAt(LocalDateTime.now(clock));
      payout.setLeaseUntil(null);
      payout.setNextRetryAt(null);
      payoutRepository.save(payout);
      // B8: a child left in REQUIRES_REVIEW or FAILED (batch exhausted its retries) needs an
      // operator — alert the admins either way.
      if (status == PayoutStatus.REQUIRES_REVIEW || status == PayoutStatus.FAILED) {
        notifyPayoutRequiresReview(payout);
      }
    }
  }

  private void completePayoutReconciliation(Long payoutId, GatewayStatusResponse providerStatus) {
    Payout payout = payoutRepository.findWithLockById(payoutId).orElse(null);
    if (payout == null || payout.getStatus() != PayoutStatus.PENDING_PROVIDER) {
      return;
    }
    String status = providerStatus == null ? "PENDING" : providerStatus.getStatus();
    LocalDateTime now = LocalDateTime.now(clock);
    if (providerStatus != null
        && notBlank(providerStatus.getExternalPaymentId())
        && !"0".equals(providerStatus.getExternalPaymentId().trim())
        && payout.getProviderPayoutId() == null) {
      payout.setProviderPayoutId(providerStatus.getExternalPaymentId());
    }
    if (providerStatus != null && providerStatus.isNotFound()) {
      LocalDateTime since = payout.getSubmittedAt();
      if (since == null || since.plusMinutes(NOT_FOUND_REVIEW_AFTER_MINUTES).isBefore(now)) {
        payout.setStatus(PayoutStatus.REQUIRES_REVIEW);
        payout.setFailureReason(
            "Provider has no record of this payout order; verify before resending");
        payout.setNextRetryAt(null);
        payoutRepository.save(payout);
        notifyPayoutRequiresReview(payout);
        return;
      }
      payout.setNextRetryAt(now.plusMinutes(PROVIDER_RECONCILIATION_DELAY_MINUTES));
      payoutRepository.save(payout);
      return;
    }
    if (providerStatus != null
        && "SUCCESS".equals(status)
        && providerStatus.getAmount() != null
        && payout.getSubmittedAmount() != null
        && providerStatus.getAmount().compareTo(payout.getSubmittedAmount()) != 0) {
      payout.setStatus(PayoutStatus.REQUIRES_REVIEW);
      payout.setFailureReason(
          "Provider payout amount "
              + providerStatus.getAmount()
              + " differs from submitted "
              + payout.getSubmittedAmount());
      payout.setNextRetryAt(null);
      payoutRepository.save(payout);
      notifyPayoutRequiresReview(payout);
      return;
    }
    if ("SUCCESS".equals(status)) {
      payout.setStatus(PayoutStatus.SUCCESS);
      payout.setFailureReason(null);
      payout.setProcessedAt(LocalDateTime.now(clock));
      payout.setNextRetryAt(null);
      appendPayoutSuccessLedger(payout);
      payoutRepository.save(payout);
      notifyPayoutSent(payout);
      return;
    }
    if ("FAILED".equals(status)) {
      payout.setStatus(PayoutStatus.REQUIRES_REVIEW);
      payout.setFailureReason(
          providerStatus.getFailureMessage() == null
              ? "Provider status reported payout failure"
              : providerStatus.getFailureMessage());
      payout.setProcessedAt(LocalDateTime.now(clock));
      payout.setNextRetryAt(null);
      payoutRepository.save(payout);
      notifyPayoutRequiresReview(payout);
      return;
    }
    // B8: a payout the provider keeps reporting as pending for over 24h is not self-healing — stop
    // polling forever and hand it to an operator.
    LocalDateTime submittedAt = payout.getSubmittedAt();
    if (submittedAt != null
        && submittedAt.plusHours(PENDING_PROVIDER_REVIEW_AFTER_HOURS).isBefore(now)) {
      payout.setStatus(PayoutStatus.REQUIRES_REVIEW);
      payout.setFailureReason("Payout stuck at provider (PENDING_PROVIDER) for over 24h");
      payout.setNextRetryAt(null);
      payoutRepository.save(payout);
      notifyPayoutRequiresReview(payout);
      return;
    }
    payout.setNextRetryAt(
        LocalDateTime.now(clock).plusMinutes(PROVIDER_RECONCILIATION_DELAY_MINUTES));
    payoutRepository.save(payout);
  }

  private void deferPayoutReconciliation(Long payoutId, String failureMessage) {
    Payout payout = payoutRepository.findWithLockById(payoutId).orElse(null);
    if (payout == null || payout.getStatus() != PayoutStatus.PENDING_PROVIDER) {
      return;
    }
    payout.setFailureReason("Status check failed: " + failureMessage);
    payout.setNextRetryAt(
        LocalDateTime.now(clock).plusMinutes(PROVIDER_RECONCILIATION_DELAY_MINUTES));
    payoutRepository.save(payout);
  }

  /**
   * The transfer request may or may not have reached FreedomPay (timeout, unreadable or unsigned
   * answer). A second reg2reg could pay the owner twice, so the payout is NEVER re-sent: it moves
   * to PENDING_PROVIDER and is reconciled with payment_status2 by its immutable order id; a
   * provider that still has no record after {@link #NOT_FOUND_REVIEW_AFTER_MINUTES} sends it to
   * review.
   */
  private void markPayoutDispatchException(Long payoutId, Exception ex) {
    Payout payout = payoutRepository.findWithLockById(payoutId).orElse(null);
    if (payout == null || payout.getStatus() != PayoutStatus.PROCESSING) {
      return;
    }
    payout.setRetryCount((payout.getRetryCount() == null ? 0 : payout.getRetryCount()) + 1);
    payout.setFailureReason("Ambiguous provider submission: " + ex.getClass().getSimpleName());
    payout.setStatus(PayoutStatus.PENDING_PROVIDER);
    payout.setLeaseUntil(null);
    payout.setNextRetryAt(
        LocalDateTime.now(clock).plusMinutes(PROVIDER_RECONCILIATION_DELAY_MINUTES));
    payoutRepository.save(payout);
  }

  private long retryBackoffSeconds(int retryCount) {
    return Math.min(300L, 30L * Math.max(1, retryCount));
  }

  /**
   * Apply an async payout result callback from the provider. Confirms a PROCESSING payout as
   * SUCCESS/FAILED by its provider payout id. Idempotent: ignores callbacks for unknown or
   * already-terminal payouts. (Used by the prod Freedom Pay flow; the dev mock settles payouts
   * synchronously and never sends this callback.)
   */
  @Transactional
  public void applyPayoutWebhook(String providerPayoutId, boolean success) {
    applyPayoutWebhook(providerPayoutId, null, success, null);
  }

  /**
   * Apply a signed payout callback. Matched by provider payment id, or by our order id when the
   * submission was ambiguous and no provider id was stored. A callback amount that differs from
   * what was submitted never settles the payout. Duplicate callbacks are no-ops.
   */
  @Transactional
  public void applyPayoutWebhook(
      String providerPayoutId, String providerOrderId, boolean success, BigDecimal amount) {
    if (notBlank(providerOrderId) && !notBlank(providerPayoutId)) {
      providerPayoutId = null;
    }
    if (!notBlank(providerPayoutId) && !notBlank(providerOrderId)) {
      log.warn("Payout webhook without provider payout id or order id, ignoring");
      return;
    }
    PayoutBatch batch =
        notBlank(providerPayoutId)
            ? payoutBatchRepository.findWithLockByProviderPayoutId(providerPayoutId).orElse(null)
            : null;
    if (batch == null && notBlank(providerOrderId)) {
      batch = payoutBatchRepository.findWithLockByProviderOrderId(providerOrderId).orElse(null);
    }
    if (batch != null) {
      if ("SUCCESS".equals(batch.getStatus())
          || "FAILED".equals(batch.getStatus())
          || "REQUIRES_REVIEW".equals(batch.getStatus())) {
        return;
      }
      if (success && amount != null && amount.compareTo(batch.getAmount()) != 0) {
        success = false;
        batch.setFailureReason(
            "Callback amount " + amount + " differs from submitted " + batch.getAmount());
        log.error("Payout batch {} callback amount differs from submitted amount", batch.getId());
      }
      if (batch.getProviderPayoutId() == null && notBlank(providerPayoutId)) {
        batch.setProviderPayoutId(providerPayoutId);
      }
      batch.setStatus(success ? "SUCCESS" : "REQUIRES_REVIEW");
      if (!success) {
        batch.setFailureReason("Provider reported payout failure");
      }
      batch.setProcessedAt(LocalDateTime.now(clock));
      batch.setNextRetryAt(null);
      payoutBatchRepository.save(batch);
      if (success) {
        completeBatchChildren(batch);
        notifyPayoutBatchSent(batch);
      } else {
        markBatchChildrenTerminal(
            batch.getId(), PayoutStatus.REQUIRES_REVIEW, batch.getFailureReason());
      }
      log.info("Payout batch {} marked {} by provider callback", batch.getId(), batch.getStatus());
      return;
    }
    Payout payout =
        notBlank(providerPayoutId)
            ? payoutRepository.findWithLockByProviderPayoutId(providerPayoutId).orElse(null)
            : null;
    if (payout == null && notBlank(providerOrderId)) {
      payout = payoutRepository.findWithLockByProviderOrderId(providerOrderId).orElse(null);
    }
    if (payout == null) {
      throw new FreedomWebhookProcessingException(
          "PAYOUT_NOT_FOUND", "Webhook references an unknown provider payout", true);
    }
    if (payout.getStatus() == PayoutStatus.SUCCESS || payout.getStatus() == PayoutStatus.FAILED) {
      return; // terminal — idempotent no-op
    }
    BigDecimal submitted =
        payout.getSubmittedAmount() == null ? payout.getAmount() : payout.getSubmittedAmount();
    if (success && amount != null && submitted != null && amount.compareTo(submitted) != 0) {
      success = false;
      payout.setFailureReason("Callback amount " + amount + " differs from submitted " + submitted);
      log.error("Payout {} callback amount differs from submitted amount", payout.getId());
    }
    if (payout.getProviderPayoutId() == null && notBlank(providerPayoutId)) {
      payout.setProviderPayoutId(providerPayoutId);
    }
    payout.setStatus(success ? PayoutStatus.SUCCESS : PayoutStatus.REQUIRES_REVIEW);
    if (!success) {
      payout.setFailureReason("Provider reported payout failure");
    }
    payout.setProcessedAt(LocalDateTime.now(clock));
    if (success) {
      appendPayoutSuccessLedger(payout);
    }
    payoutRepository.save(payout);
    log.info("Payout {} marked {} by provider callback", payout.getId(), payout.getStatus().name());

    if (success) {
      notifyPayoutSent(payout);
    } else {
      notifyPayoutRequiresReview(payout);
    }
  }

  /** One-shot admin alert that a payout is stuck in REQUIRES_REVIEW and needs an operator. */
  private void notifyPayoutRequiresReview(Payout payout) {
    if (payout.getId() == null) return;
    notificationService.notifyAdminsOnce(
        kz.hrms.splitupauth.entity.NotificationType.PAYOUT_REQUIRES_REVIEW,
        payout.getId(),
        java.util.Map.of("entityId", String.valueOf(payout.getId())),
        "/admin/finance",
        java.util.Map.of("payoutId", payout.getId()));
  }

  private void notifyPayoutSent(Payout payout) {
    if (payout.getUser() == null) return;
    notificationService.notify(
        payout.getUser(),
        kz.hrms.splitupauth.entity.NotificationType.PAYOUT_SENT,
        java.util.Map.of(
            "amount",
            String.valueOf(payout.getAmount()),
            "currency",
            payout.getCurrency() == null ? "KZT" : payout.getCurrency()),
        "/payment/payout",
        java.util.Map.of("payoutId", payout.getId()));
  }

  private void notifyPayoutBatchSent(PayoutBatch batch) {
    if (batch.getOwner() == null) return;
    notificationService.notify(
        batch.getOwner(),
        kz.hrms.splitupauth.entity.NotificationType.PAYOUT_SENT,
        java.util.Map.of(
            "amount",
            String.valueOf(batch.getAmount()),
            "currency",
            batch.getCurrency() == null ? "KZT" : batch.getCurrency()),
        "/payment/payout",
        java.util.Map.of("payoutBatchId", batch.getId()));
  }

  private void appendPayoutSuccessLedger(Payout payout) {
    BigDecimal amount =
        payout.getSubmittedAmount() == null ? payout.getAmount() : payout.getSubmittedAmount();
    moneyLedgerService.append(
        "HOLD_RELEASE",
        amount,
        payout.getCurrency(),
        "DEBIT",
        payout.getTriggeringPaymentIntent(),
        null,
        null,
        payout,
        payout.getUser(),
        "hold-release-payout-" + payout.getId());
    moneyLedgerService.append(
        "PAYOUT",
        amount,
        payout.getCurrency(),
        "DEBIT",
        payout.getTriggeringPaymentIntent(),
        null,
        null,
        payout,
        payout.getUser(),
        "payout-" + payout.getId());
  }

  /**
   * Clawback hook: called when a charge is refunded. If the owner payout that the charge triggered
   * has not been paid out yet (PENDING/PENDING_METHOD) and the charge was fully refunded, reverse
   * the payout so the platform never pays out refunded money. If the payout was already dispatched
   * (PROCESSING/SUCCESS), it cannot be auto-recovered — we flag it for manual clawback via an audit
   * event. Partial refunds on a not-yet-paid payout are also flagged (proportional recompute is an
   * accounting decision).
   */
  @Transactional
  public void reverseOwnerPayoutForRefund(PaymentIntent triggeringIntent, boolean fullRefund) {
    if (triggeringIntent == null) {
      return;
    }
    Payout payout = payoutRepository.findByTriggeringPaymentIntent(triggeringIntent).orElse(null);
    if (payout == null) {
      return;
    }
    String status = payout.getStatus().name();
    boolean notYetPaid = "PENDING".equals(status) || "PENDING_METHOD".equals(status);

    if (notYetPaid && fullRefund) {
      String old = payout.getStatus().name();
      payout.setStatus(PayoutStatus.REVERSED);
      payout.setFailureReason("Reversed: triggering charge was refunded before payout");
      payout.setProcessedAt(LocalDateTime.now(clock));
      payoutRepository.save(payout);
      eventLogger.log(
          "PAYOUT",
          payout.getId(),
          "REVERSED",
          old,
          "REVERSED",
          null,
          null,
          payout.getIdempotencyKey(),
          java.util.Map.of("reason", "charge_refunded"));
      log.info("Payout {} reversed (charge refunded before payout)", payout.getId());
    } else {
      // Either already dispatched/paid, or a partial refund on a pending payout:
      // cannot safely auto-adjust — record for manual clawback / review.
      eventLogger.log(
          "PAYOUT",
          payout.getId(),
          "CLAWBACK_REQUIRED",
          status,
          status,
          null,
          null,
          payout.getIdempotencyKey(),
          java.util.Map.of("fullRefund", String.valueOf(fullRefund)));
      log.warn(
          "Payout {} (status {}) needs manual clawback — its charge was refunded (full={})",
          payout.getId(),
          status,
          fullRefund);
    }
  }

  /**
   * Recomputes the unpaid owner share from provider-confirmed refunds only. Pending refund requests
   * freeze dispatch elsewhere but never reduce accounting balances until they succeed.
   */
  @Transactional
  public void adjustOwnerPayoutForSuccessfulRefund(
      PaymentIntent triggeringIntent, BigDecimal successfulRefundTotal) {
    if (triggeringIntent == null || successfulRefundTotal == null) return;
    Payout existing = payoutRepository.findByTriggeringPaymentIntent(triggeringIntent).orElse(null);
    if (existing == null) return;
    Payout payout = payoutRepository.findWithLockById(existing.getId()).orElse(null);
    if (payout == null) return;

    String status = payout.getStatus().name();
    boolean notYetDispatched =
        payout.getPayoutBatch() == null
            && payout.getSubmittedAmount() == null
            && ("PENDING".equals(status)
                || "PENDING_METHOD".equals(status)
                || "FROZEN".equals(status));
    BigDecimal totalCharged = triggeringIntent.getAmount();
    BigDecimal commission =
        triggeringIntent.getCommissionAmount() == null
            ? BigDecimal.ZERO
            : triggeringIntent.getCommissionAmount();
    BigDecimal originalShare =
        payout.getOriginalAmount() == null
            ? totalCharged.subtract(commission)
            : payout.getOriginalAmount();
    BigDecimal cappedRefund = successfulRefundTotal.min(totalCharged).max(BigDecimal.ZERO);
    BigDecimal refundedShare =
        cappedRefund.compareTo(totalCharged) >= 0
            ? originalShare
            : originalShare
                .multiply(cappedRefund)
                .divide(totalCharged, 2, RoundingMode.HALF_UP)
                .min(originalShare);
    BigDecimal payable = originalShare.subtract(refundedShare).max(BigDecimal.ZERO).setScale(2);

    payout.setOriginalAmount(originalShare.setScale(2));
    payout.setRefundedShareAmount(refundedShare.setScale(2));
    payout.setPayableAmount(payable);
    if (!notYetDispatched) {
      BigDecimal submitted =
          payout.getSubmittedAmount() == null ? payout.getAmount() : payout.getSubmittedAmount();
      BigDecimal exposure = submitted.subtract(payable).max(BigDecimal.ZERO).setScale(2);
      payout.setClawbackAmount(exposure);
      payout.setClawbackRequired(exposure.signum() > 0);
      payoutRepository.save(payout);
      eventLogger.log(
          "PAYOUT",
          payout.getId(),
          "CLAWBACK_REQUIRED",
          status,
          status,
          null,
          null,
          payout.getIdempotencyKey(),
          java.util.Map.of(
              "successfulRefundTotal", successfulRefundTotal.toPlainString(),
              "clawbackAmount", exposure.toPlainString()));
      return;
    }
    payout.setAmount(payable);
    if (payable.signum() == 0) {
      payout.setStatus(PayoutStatus.REVERSED);
      payout.setProcessedAt(LocalDateTime.now(clock));
      payout.setFailureReason("Reversed: successful refunds exhausted owner share");
    }
    payoutRepository.save(payout);
    eventLogger.log(
        "PAYOUT",
        payout.getId(),
        "REFUND_ADJUSTED",
        status,
        payout.getStatus().name(),
        null,
        null,
        payout.getIdempotencyKey(),
        java.util.Map.of(
            "refundedShare", refundedShare.toPlainString(), "payable", payable.toPlainString()));
  }

  @Transactional(readOnly = true)
  public List<Payout> listMine(User user) {
    return payoutRepository.findByUserOrderByCreatedAtDesc(user);
  }

  /**
   * Returns the owner's current held balance.
   *
   * <p>A payout counts as held only while it remains in a pre-dispatch status and its release time
   * is still in the future. Reversed/refunded, failed, processing, successful, and already-due
   * payouts therefore cannot inflate the balance.
   */
  @Transactional(readOnly = true)
  public PayoutBalanceDto getHeldBalance(User user) {
    LocalDateTime calculatedAt = LocalDateTime.now(clock);
    List<Payout> held =
        payoutRepository.findByUserAndCurrencyAndStatusInAndReleaseAtAfterOrderByReleaseAtAsc(
            user, PayoutHoldPolicy.CURRENCY, PayoutHoldPolicy.HELD_STATUSES, calculatedAt);

    BigDecimal heldAmount =
        held.stream()
            .map(Payout::getPayableAmount)
            .filter(java.util.Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);
    LocalDateTime nextReleaseAt =
        held.stream()
            .map(Payout::getReleaseAt)
            .filter(java.util.Objects::nonNull)
            .min(LocalDateTime::compareTo)
            .orElse(null);

    return PayoutBalanceDto.builder()
        .heldAmount(heldAmount)
        .currency(PayoutHoldPolicy.CURRENCY)
        .heldPayoutCount(held.size())
        .nextReleaseAt(nextReleaseAt)
        .calculatedAt(calculatedAt)
        .build();
  }

  @Transactional(readOnly = true)
  public Payout getMine(User user, Long id) {
    Payout p =
        payoutRepository
            .findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Payout not found"));
    if (!p.getUser().getId().equals(user.getId())) {
      throw new ForbiddenOperationException("Not your payout");
    }
    return p;
  }

  /**
   * Formerly registered a purchase saved-card token as payout destination. FreedomPay purchase /
   * recurring tokens are not proven valid for reg2reg, so this path is closed: payout methods are
   * created only by the payout-card binding flow ({@link #registerVerifiedPayoutMethod}).
   */
  @Transactional
  public PayoutMethod registerMethod(User user, String providerCardToken, String panMask) {
    throw new InvalidRequestException(
        "PAYOUT_CARD_BINDING_REQUIRED: connect a payout card through the payout card flow");
  }

  @Transactional
  public PayoutMethod registerVerifiedPayoutMethod(
      User user, String providerCardToken, String panMask) {
    if (providerCardToken == null || providerCardToken.isBlank()) {
      throw new InvalidRequestException("providerCardToken is required");
    }
    // B3: a reconnect of the same card (same provider token, or the same card returned under a new
    // token but the same masked PAN) must not create a second method — reuse the existing one.
    PayoutMethod already =
        payoutMethodRepository
            .findByUserAndProviderCardTokenAndStatus(user, providerCardToken, "ACTIVE")
            .orElse(null);
    if (already == null && notBlank(panMask)) {
      already =
          payoutMethodRepository
              .findByUserAndStatusOrderByIsDefaultDescCreatedAtDesc(
                  user, PayoutMethod.STATUS_ACTIVE)
              .stream()
              .filter(m -> panMask.equals(m.getPanMask()))
              .findFirst()
              .orElse(null);
    }
    if (already != null) {
      if (Boolean.TRUE.equals(already.getIsDefault())) {
        reactivatePendingMethodPayouts(user);
      }
      return already;
    }
    boolean firstMethod =
        payoutMethodRepository.findByUserAndIsDefaultTrueAndStatus(user, "ACTIVE").isEmpty();
    PayoutMethod method =
        PayoutMethod.builder()
            .user(user)
            .providerName(FreedomPayGateway.PROVIDER_NAME)
            .providerCardToken(providerCardToken)
            .panMask(panMask)
            .isDefault(firstMethod)
            .status(PayoutMethod.STATUS_ACTIVE)
            .tokenSource(PayoutMethod.TOKEN_SOURCE_PAYOUT_CARD)
            .verifiedAt(LocalDateTime.now(clock))
            .build();
    method = payoutMethodRepository.save(method);
    // A newly connected default card unblocks any payouts that were waiting for a method.
    if (firstMethod) {
      reactivatePendingMethodPayouts(user);
    }
    return method;
  }

  /** Active methods plus legacy ones the owner must reconnect (flagged requiresRebind). */
  @Transactional(readOnly = true)
  public List<PayoutMethod> listMethods(User user) {
    List<PayoutMethod> methods =
        new ArrayList<>(
            payoutMethodRepository.findByUserAndStatusOrderByIsDefaultDescCreatedAtDesc(
                user, PayoutMethod.STATUS_ACTIVE));
    methods.addAll(
        payoutMethodRepository.findByUserAndStatusOrderByIsDefaultDescCreatedAtDesc(
            user, PayoutMethod.STATUS_REQUIRES_REBIND));
    return methods;
  }

  /**
   * The owner's default destination, only if its token came from payout-card tokenization. Anything
   * else (legacy, purchase token, revoked) is never paid to: the payout waits in PENDING_METHOD.
   */
  private PayoutMethod dispatchableMethod(User owner) {
    PayoutMethod method =
        payoutMethodRepository
            .findByUserAndIsDefaultTrueAndStatus(owner, PayoutMethod.STATUS_ACTIVE)
            .orElse(null);
    if (method == null || !method.isPayoutCompatible()) {
      return null;
    }
    return method;
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private void backfillProviderId(PayoutBatch batch, GatewayStatusResponse providerStatus) {
    if (providerStatus != null
        && batch.getProviderPayoutId() == null
        && notBlank(providerStatus.getExternalPaymentId())
        && !"0".equals(providerStatus.getExternalPaymentId().trim())) {
      batch.setProviderPayoutId(providerStatus.getExternalPaymentId());
    }
  }

  /**
   * B3: disconnect an owner payout card. The provider token is deleted best-effort, then the method
   * is revoked locally. If it was the owner's last active card, any still-pending payouts (PENDING
   * / ON_HOLD) move to PENDING_METHOD so they wait for a new card instead of failing. Returns how
   * many payouts were affected so the UI can warn the owner.
   */
  @Transactional
  public int revokeMethod(User user, Long id) {
    PayoutMethod method =
        payoutMethodRepository
            .findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Payout method not found"));
    if (!method.getUser().getId().equals(user.getId())) {
      throw new ForbiddenOperationException("Not your method");
    }
    if ("REVOKED".equals(method.getStatus())) {
      return 0; // idempotent
    }

    boolean lastActiveCard =
        payoutMethodRepository
            .findByUserAndStatusOrderByIsDefaultDescCreatedAtDesc(user, PayoutMethod.STATUS_ACTIVE)
            .stream()
            .noneMatch(m -> !m.getId().equals(method.getId()));

    if (notBlank(method.getProviderCardToken())) {
      try {
        gatewayRegistry.defaultGateway().deletePayoutCardToken(method.getProviderCardToken());
      } catch (Exception ex) {
        log.warn("Provider payout-card token delete failed for method {}: {}", id, ex.getMessage());
      }
    }

    method.setStatus("REVOKED");
    method.setIsDefault(false);
    method.setRevokedAt(LocalDateTime.now(clock));
    payoutMethodRepository.save(method);

    if (!lastActiveCard) {
      return 0;
    }
    // No card remains: pending payouts cannot be routed, so park them until a new card is
    // connected.
    List<Payout> affected =
        payoutRepository.findByUserAndStatusIn(
            user, List.of(PayoutStatus.PENDING, PayoutStatus.ON_HOLD));
    int count = 0;
    for (Payout payout : affected) {
      if (payout.getPayoutBatch() != null) {
        continue; // a batched payout is mid-dispatch; leave it to the batch flow
      }
      payout.setStatus(PayoutStatus.PENDING_METHOD);
      payout.setNextRetryAt(null);
      payout.setLeaseUntil(null);
      payoutRepository.save(payout);
      count++;
    }
    return count;
  }

  /**
   * B3: a usable payout card just became the owner's default — release every payout that was parked
   * in PENDING_METHOD so the next dispatch tick sends it.
   */
  private void reactivatePendingMethodPayouts(User user) {
    LocalDateTime now = LocalDateTime.now(clock);
    for (Payout payout :
        payoutRepository.findByUserAndStatusIn(user, List.of(PayoutStatus.PENDING_METHOD))) {
      payout.setStatus(PayoutStatus.PENDING);
      payout.setNextRetryAt(now);
      payout.setFailureReason(null);
      payoutRepository.save(payout);
    }
  }

  /**
   * B8: admin re-queue of a stuck payout. Re-dispatch is idempotent: a previously-submitted payout
   * (providerOrderId + submittedAmount set) runs the B2 pre-dispatch status check first, so a
   * payout already at the provider is never sent twice. Terminal SUCCESS/REVERSED/CANCELED are not
   * retryable.
   */
  @Transactional
  public void retryStuckPayout(Long id) {
    Payout payout =
        payoutRepository
            .findWithLockById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Payout not found"));
    switch (payout.getStatus()) {
      case REQUIRES_REVIEW, PENDING_PROVIDER, ON_HOLD, PENDING_METHOD, FAILED, PROCESSING -> {
        payout.setStatus(PayoutStatus.PENDING);
        payout.setRetryCount(0);
        payout.setNextRetryAt(LocalDateTime.now(clock));
        payout.setLeaseUntil(null);
        payoutRepository.save(payout);
        eventLogger.log(
            "PAYOUT",
            payout.getId(),
            "ADMIN_RETRY",
            null,
            PayoutStatus.PENDING.name(),
            null,
            null,
            payout.getIdempotencyKey(),
            java.util.Map.of());
      }
      default ->
          throw new InvalidRequestException(
              "PAYOUT_NOT_RETRYABLE", "Payout is not in a retryable state");
    }
  }

  @Transactional(readOnly = true)
  public List<Payout> findStuck() {
    return payoutRepository.findByStatusInOrderByCreatedAtAsc(
        List.of(
            PayoutStatus.PENDING_PROVIDER,
            PayoutStatus.PROCESSING,
            PayoutStatus.REQUIRES_REVIEW,
            PayoutStatus.ON_HOLD,
            PayoutStatus.FAILED));
  }

  private TransactionTemplate tx() {
    return new TransactionTemplate(transactionManager);
  }

  private BigDecimal payoutPayableAmount(Payout payout) {
    BigDecimal amount =
        payout.getPayableAmount() == null ? payout.getAmount() : payout.getPayableAmount();
    return amount == null ? BigDecimal.ZERO.setScale(2) : amount.setScale(2, RoundingMode.HALF_UP);
  }

  private String batchIdempotencyKey(List<Long> payoutIds) {
    String source = "payout-batch:" + payoutIds;
    UUID id = UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    return "payout-batch-" + id;
  }

  private String payoutProviderOrderId(Payout payout) {
    return payout.getProviderOrderId() == null
        ? String.valueOf(payout.getId())
        : payout.getProviderOrderId();
  }

  private List<Payout> verifyBatchSnapshot(PayoutBatch batch) {
    List<Payout> children = payoutRepository.findWithLockByPayoutBatchId(batch.getId());
    BigDecimal total = BigDecimal.ZERO;
    for (Payout child : children) {
      if (child.getSubmittedAmount() == null || child.getSubmittedAmount().signum() <= 0) {
        throw new IllegalStateException("Missing frozen payout amount in batch " + batch.getId());
      }
      total = total.add(child.getSubmittedAmount());
    }
    if (total.compareTo(batch.getAmount()) != 0) {
      throw new IllegalStateException(
          "Batch amount differs from frozen children: " + batch.getId());
    }
    return children;
  }

  private record BatchGroupKey(Long ownerId, Long payoutMethodId, String currency) {}

  private record BatchCandidate(Long payoutId, BatchGroupKey groupKey) {}

  private record DispatchClaim(
      Long payoutId,
      String idempotencyKey,
      String destinationUserId,
      String destinationCardToken,
      BigDecimal amount,
      String currency,
      String providerOrderId,
      String description,
      String previousOrderId) {}

  private record BatchDispatchClaim(
      Long batchId,
      String idempotencyKey,
      String destinationUserId,
      String destinationCardToken,
      BigDecimal amount,
      String currency,
      String providerOrderId) {}
}
