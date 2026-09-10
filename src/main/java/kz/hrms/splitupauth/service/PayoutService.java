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
import kz.hrms.splitupauth.entity.SavedCardStatus;
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
import kz.hrms.splitupauth.repository.PayoutMethodRepository;
import kz.hrms.splitupauth.repository.PayoutRepository;
import kz.hrms.splitupauth.repository.PayoutBatchRepository;
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
  private static final String PAYOUT_CURRENCY = "KZT";
  private static final List<String> HELD_STATUSES = List.of("PENDING", "PENDING_METHOD", "FROZEN");
  private static final int DISPATCH_LEASE_MINUTES = 5;
  private static final int PROVIDER_RECONCILIATION_DELAY_MINUTES = 5;

  private final PayoutRepository payoutRepository;
  private final PayoutBatchRepository payoutBatchRepository;
  private final PayoutMethodRepository payoutMethodRepository;
  private final PaymentGatewayRegistry gatewayRegistry;
  private final PaymentEventLogger eventLogger;
  private final SavedCardRepository savedCardRepository;
  private final NotificationService notificationService;
  private final MoneyLedgerService moneyLedgerService;
  private final PayoutEligibilityService payoutEligibilityService;
  private final Clock clock;
  private final PlatformTransactionManager transactionManager;

  /**
   * Days a captured payment is held in the merchant balance before the owner payout is dispatched.
   */
  @Value("${app.payout.hold-days:30}")
  private int payoutHoldDays;

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
    LocalDateTime releaseAt = capturedAt.plusDays(payoutHoldDays);

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
            .status("PENDING")
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
        payout.getStatus(),
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
            String.valueOf(payoutHoldDays)));

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
        payoutRepository.findDispatchable(List.of("PENDING", "PENDING_METHOD"), now);
    Map<BatchGroupKey, List<Payout>> grouped = new LinkedHashMap<>();
    for (Payout payout : pending) {
      if ("PROCESSING".equals(payout.getStatus())) {
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

    for (List<Payout> group : grouped.values()) {
      try {
        if (group.size() > 1) {
          dispatchNewPayoutBatch(group.stream().map(Payout::getId).toList());
        } else {
          Payout payout = group.get(0);
          if (!shouldWaitForCoalescing(payout, now)) {
            dispatchPayout(payout.getId());
          }
        }
      } catch (Exception ex) {
        log.error("Payout group dispatch failed: {}", ex.getMessage());
      }
    }
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
      if (payout.getProviderPayoutId() == null || payout.getProviderPayoutId().isBlank()) {
        continue;
      }
      try {
        PaymentGateway gateway = gatewayRegistry.defaultGateway();
        GatewayStatusResponse providerStatus =
            gateway.getPayoutStatus(
                payout.getProviderPayoutId(), payoutProviderOrderId(payout));
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
      if (batch.getProviderPayoutId() == null || batch.getProviderPayoutId().isBlank()) {
        continue;
      }
      try {
        PaymentGateway gateway = gatewayRegistry.defaultGateway();
        GatewayStatusResponse providerStatus =
            gateway.getPayoutStatus(batch.getProviderPayoutId(), batch.getProviderOrderId());
        tx().executeWithoutResult(
            status -> completeBatchReconciliation(batch.getId(), providerStatus));
      } catch (Exception ex) {
        log.warn("Payout batch {} status reconciliation failed: {}", batch.getId(), ex.getMessage());
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
                  .description("EcoPay payout #" + claim.payoutId())
                  .build());
    } catch (Exception ex) {
      tx().executeWithoutResult(status -> markPayoutDispatchException(claim.payoutId(), ex));
      return;
    }
    String finalProviderName = providerName;
    tx().executeWithoutResult(
            status -> completePayoutDispatch(claim.payoutId(), resp, finalProviderName));
  }

  private BatchCandidate prepareBatchCandidate(Long payoutId) {
    return tx().execute(
        status -> {
          Payout payout = payoutRepository.findWithLockById(payoutId).orElse(null);
          if (payout == null || payout.getPayoutBatch() != null) {
            return null;
          }
          if (!"PENDING".equals(payout.getStatus()) && !"PENDING_METHOD".equals(payout.getStatus())) {
            return null;
          }
          LocalDateTime now = LocalDateTime.now(clock);
          if ((payout.getReleaseAt() != null && payout.getReleaseAt().isAfter(now))
              || (payout.getNextRetryAt() != null && payout.getNextRetryAt().isAfter(now))) {
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
                payout.getStatus(),
                payout.getStatus(),
                null,
                null,
                payout.getIdempotencyKey(),
                java.util.Map.of("reason", eligibility.reason()));
            return null;
          }
          PayoutMethod method =
              payoutMethodRepository
                  .findByUserAndIsDefaultTrueAndStatus(payout.getUser(), "ACTIVE")
                  .orElse(null);
          if (method == null) {
            payout.setStatus("PENDING_METHOD");
            payoutRepository.save(payout);
            return null;
          }
          if (payout.getRetryCount() != null && payout.getRetryCount() >= MAX_RETRY) {
            payout.setStatus("FAILED");
            payout.setFailureReason("Max retries exceeded");
            payoutRepository.save(payout);
            return null;
          }
          BigDecimal payable = payoutPayableAmount(payout);
          if (payable.signum() <= 0) {
            payout.setStatus("REVERSED");
            payout.setFailureReason("Reversed: payable amount is zero");
            payout.setProcessedAt(now);
            payoutRepository.save(payout);
            return null;
          }
          return new BatchCandidate(
              payout.getId(),
              new BatchGroupKey(payout.getUser().getId(), method.getId(), payout.getCurrency()));
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
                  .description("EcoPay payout batch #" + claim.batchId())
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
    PayoutMethod method =
        payoutMethodRepository
            .findByUserAndIsDefaultTrueAndStatus(first.getUser(), "ACTIVE")
            .orElse(null);
    if (method == null) {
      return null;
    }
    BigDecimal total = BigDecimal.ZERO;
    for (Payout payout : payouts) {
      if (payout.getPayoutBatch() != null
          || (!"PENDING".equals(payout.getStatus()) && !"PENDING_METHOD".equals(payout.getStatus()))
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
      PayoutMethod currentMethod =
          payoutMethodRepository
              .findByUserAndIsDefaultTrueAndStatus(payout.getUser(), "ACTIVE")
              .orElse(null);
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
      payout.setStatus("PROCESSING");
      payout.setLeaseUntil(now.plusMinutes(DISPATCH_LEASE_MINUTES));
      payout.setAmount(payoutPayableAmount(payout));
      payout.setSubmittedAmount(payoutPayableAmount(payout));
    }
    payoutRepository.saveAll(payouts);
    return new BatchDispatchClaim(
        batch.getId(),
        batch.getIdempotencyKey(),
        String.valueOf(first.getUser().getId()),
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
      markBatchChildrenTerminal(batch.getId(), "FAILED", "Max retries exceeded");
      return null;
    }
    PaymentGateway gateway = gatewayRegistry.defaultGateway();
    if (!gateway.supportsIdempotentPayoutReplay()
        || !gateway.providerName().equals(batch.getProviderName())) {
      batch.setStatus("REQUIRES_REVIEW");
      batch.setFailureReason("Submission may have succeeded; automatic replay is not safe");
      markBatchChildrenTerminal(batchId, "REQUIRES_REVIEW", batch.getFailureReason());
      return null;
    }
    verifyBatchSnapshot(batch);
    batch.setLeaseUntil(now.plusMinutes(DISPATCH_LEASE_MINUTES));
    payoutBatchRepository.save(batch);
    return new BatchDispatchClaim(
        batch.getId(),
        batch.getIdempotencyKey(),
        String.valueOf(batch.getOwner().getId()),
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
        "PROCESSING".equals(payout.getStatus())
            && payout.getProviderPayoutId() == null
            && payout.getLeaseUntil() != null
            && !payout.getLeaseUntil().isAfter(now);
    if (!staleProcessing
        && !"PENDING".equals(payout.getStatus())
        && !"PENDING_METHOD".equals(payout.getStatus())) {
      return null;
    }
    if (payout.getSubmittedAmount() != null) {
      // Legacy individual dispatches have no complete persisted retry payload.
      payout.setStatus("REQUIRES_REVIEW");
      payout.setFailureReason("Prior submission requires reconciliation before any new transfer");
      payoutRepository.save(payout);
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
          payout.getStatus(),
          payout.getStatus(),
          null,
          null,
          payout.getIdempotencyKey(),
          java.util.Map.of("reason", eligibility.reason()));
      return null;
    }

    PayoutMethod method =
        payoutMethodRepository
            .findByUserAndIsDefaultTrueAndStatus(payout.getUser(), "ACTIVE")
            .orElse(null);
    if (method == null) {
      payout.setStatus("PENDING_METHOD");
      payoutRepository.save(payout);
      return null;
    }

    if (payout.getRetryCount() != null && payout.getRetryCount() >= MAX_RETRY) {
      payout.setStatus("FAILED");
      payout.setFailureReason("Max retries exceeded");
      payoutRepository.save(payout);
      return null;
    }

    payout.setStatus("PROCESSING");
    payout.setLeaseUntil(now.plusMinutes(DISPATCH_LEASE_MINUTES));
    payout.setPayoutMethod(method);
    payout.setSubmittedAmount(payoutPayableAmount(payout));
    payout.setAmount(payout.getSubmittedAmount());
    if (payout.getProviderOrderId() == null) {
      payout.setProviderOrderId("ecopay-payout-" + payout.getId());
    }
    payout = payoutRepository.save(payout);
    return new DispatchClaim(
        payout.getId(),
        payout.getIdempotencyKey(),
        String.valueOf(payout.getUser().getId()),
        method.getProviderCardToken(),
        payout.getAmount(),
        payout.getCurrency(),
        payout.getProviderOrderId());
  }

  private void completePayoutDispatch(
      Long payoutId, GatewayPayoutResponse resp, String providerName) {
    Payout payout = payoutRepository.findWithLockById(payoutId).orElse(null);
    if (payout == null || !"PROCESSING".equals(payout.getStatus())) {
      return;
    }
    if (resp.isSuccess() && !FreedomPayGateway.PROVIDER_NAME.equalsIgnoreCase(providerName)) {
      payout.setStatus("SUCCESS");
      payout.setProviderPayoutId(resp.getExternalPayoutId());
      payout.setProcessedAt(LocalDateTime.now(clock));
      payout.setLeaseUntil(null);
      payout.setNextRetryAt(null);
      appendPayoutSuccessLedger(payout);
    } else if (resp.isPending() || resp.isSuccess()) {
      payout.setStatus("PENDING_PROVIDER");
      payout.setProviderPayoutId(resp.getExternalPayoutId());
      payout.setLeaseUntil(null);
      payout.setNextRetryAt(
          LocalDateTime.now(clock).plusMinutes(PROVIDER_RECONCILIATION_DELAY_MINUTES));
    } else {
      payout.setRetryCount((payout.getRetryCount() == null ? 0 : payout.getRetryCount()) + 1);
      payout.setFailureReason(resp.getFailureMessage());
      payout.setStatus(payout.getRetryCount() >= MAX_RETRY ? "FAILED" : "PENDING");
      payout.setLeaseUntil(null);
      payout.setNextRetryAt(
          payout.getRetryCount() >= MAX_RETRY
              ? null
              : LocalDateTime.now(clock).plusSeconds(retryBackoffSeconds(payout.getRetryCount())));
    }
    payoutRepository.save(payout);
  }

  private void completeBatchDispatch(
      Long batchId, GatewayPayoutResponse resp, String providerName) {
    PayoutBatch batch = payoutBatchRepository.findWithLockById(batchId).orElse(null);
    if (batch == null || !"PROCESSING".equals(batch.getStatus())) {
      return;
    }
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
        markBatchChildrenTerminal(batch.getId(), "FAILED", resp.getFailureMessage());
      }
    }
  }

  private void completeBatchReconciliation(
      Long batchId, GatewayStatusResponse providerStatus) {
    PayoutBatch batch = payoutBatchRepository.findWithLockById(batchId).orElse(null);
    if (batch == null || !"PENDING_PROVIDER".equals(batch.getStatus())) {
      return;
    }
    String status = providerStatus == null ? "PENDING" : providerStatus.getStatus();
    LocalDateTime now = LocalDateTime.now(clock);
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
      markBatchChildrenTerminal(batch.getId(), "REQUIRES_REVIEW", batch.getFailureReason());
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
      batch.setStatus("REQUIRES_REVIEW");
      batch.setFailureReason("Ambiguous provider submission: " + ex.getMessage());
      batch.setLeaseUntil(null);
      batch.setNextRetryAt(null);
      markBatchChildrenTerminal(batchId, "REQUIRES_REVIEW", batch.getFailureReason());
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
      markBatchChildrenTerminal(batch.getId(), "FAILED", ex.getMessage());
    }
  }

  private void completeBatchChildren(PayoutBatch batch) {
    for (Payout payout : verifyBatchSnapshot(batch)) {
      if ("SUCCESS".equals(payout.getStatus())) {
        appendPayoutSuccessLedger(payout);
        continue;
      }
      payout.setStatus("SUCCESS");
      payout.setFailureReason(null);
      payout.setProcessedAt(LocalDateTime.now(clock));
      payout.setLeaseUntil(null);
      payout.setNextRetryAt(null);
      payout.setAmount(payout.getSubmittedAmount());
      payoutRepository.save(payout);
      appendPayoutSuccessLedger(payout);
    }
  }

  private void markBatchChildrenTerminal(Long batchId, String status, String failureReason) {
    for (Payout payout : payoutRepository.findWithLockByPayoutBatchId(batchId)) {
      if ("SUCCESS".equals(payout.getStatus())) {
        continue;
      }
      payout.setStatus(status);
      payout.setFailureReason(failureReason);
      payout.setProcessedAt(LocalDateTime.now(clock));
      payout.setLeaseUntil(null);
      payout.setNextRetryAt(null);
      payoutRepository.save(payout);
    }
  }

  private void completePayoutReconciliation(
      Long payoutId, GatewayStatusResponse providerStatus) {
    Payout payout = payoutRepository.findWithLockById(payoutId).orElse(null);
    if (payout == null || !"PENDING_PROVIDER".equals(payout.getStatus())) {
      return;
    }
    String status = providerStatus == null ? "PENDING" : providerStatus.getStatus();
    if ("SUCCESS".equals(status)) {
      payout.setStatus("SUCCESS");
      payout.setFailureReason(null);
      payout.setProcessedAt(LocalDateTime.now(clock));
      payout.setNextRetryAt(null);
      appendPayoutSuccessLedger(payout);
      payoutRepository.save(payout);
      notifyPayoutSent(payout);
      return;
    }
    if ("FAILED".equals(status)) {
      payout.setStatus("REQUIRES_REVIEW");
      payout.setFailureReason(
          providerStatus.getFailureMessage() == null
              ? "Provider status reported payout failure"
              : providerStatus.getFailureMessage());
      payout.setProcessedAt(LocalDateTime.now(clock));
      payout.setNextRetryAt(null);
      payoutRepository.save(payout);
      return;
    }
    payout.setNextRetryAt(
        LocalDateTime.now(clock).plusMinutes(PROVIDER_RECONCILIATION_DELAY_MINUTES));
    payoutRepository.save(payout);
  }

  private void deferPayoutReconciliation(Long payoutId, String failureMessage) {
    Payout payout = payoutRepository.findWithLockById(payoutId).orElse(null);
    if (payout == null || !"PENDING_PROVIDER".equals(payout.getStatus())) {
      return;
    }
    payout.setFailureReason("Status check failed: " + failureMessage);
    payout.setNextRetryAt(
        LocalDateTime.now(clock).plusMinutes(PROVIDER_RECONCILIATION_DELAY_MINUTES));
    payoutRepository.save(payout);
  }

  private void markPayoutDispatchException(Long payoutId, Exception ex) {
    Payout payout = payoutRepository.findWithLockById(payoutId).orElse(null);
    if (payout == null || !"PROCESSING".equals(payout.getStatus())) {
      return;
    }
    payout.setRetryCount((payout.getRetryCount() == null ? 0 : payout.getRetryCount()) + 1);
    payout.setFailureReason(ex.getMessage());
    payout.setStatus(payout.getRetryCount() >= MAX_RETRY ? "FAILED" : "PENDING");
    payout.setLeaseUntil(null);
    payout.setNextRetryAt(
        payout.getRetryCount() >= MAX_RETRY
            ? null
            : LocalDateTime.now(clock).plusSeconds(retryBackoffSeconds(payout.getRetryCount())));
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
    if (providerPayoutId == null || providerPayoutId.isBlank()) {
      log.warn("Payout webhook without provider payout id, ignoring");
      return;
    }
    PayoutBatch batch =
        payoutBatchRepository.findWithLockByProviderPayoutId(providerPayoutId).orElse(null);
    if (batch != null) {
      if ("SUCCESS".equals(batch.getStatus())
          || "FAILED".equals(batch.getStatus())
          || "REQUIRES_REVIEW".equals(batch.getStatus())) {
        return;
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
        markBatchChildrenTerminal(batch.getId(), "REQUIRES_REVIEW", batch.getFailureReason());
      }
      log.info("Payout batch {} marked {} by provider callback", batch.getId(), batch.getStatus());
      return;
    }
    Payout payout = payoutRepository.findWithLockByProviderPayoutId(providerPayoutId).orElse(null);
    if (payout == null) {
      throw new FreedomWebhookProcessingException(
          "PAYOUT_NOT_FOUND",
          "Webhook references unknown provider payout " + providerPayoutId,
          true);
    }
    if ("SUCCESS".equals(payout.getStatus()) || "FAILED".equals(payout.getStatus())) {
      return; // terminal — idempotent no-op
    }
    payout.setStatus(success ? "SUCCESS" : "REQUIRES_REVIEW");
    if (!success) {
      payout.setFailureReason("Provider reported payout failure");
    }
    payout.setProcessedAt(LocalDateTime.now(clock));
    if (success) {
      appendPayoutSuccessLedger(payout);
    }
    payoutRepository.save(payout);
    log.info("Payout {} marked {} by provider callback", payout.getId(), payout.getStatus());

    if (success) {
      notifyPayoutSent(payout);
    }
  }

  private void notifyPayoutSent(Payout payout) {
    if (payout.getUser() == null) return;
    notificationService.notify(
        payout.getUser(),
        kz.hrms.splitupauth.entity.NotificationType.PAYOUT_SENT,
        "Выплата отправлена",
        "Выплата на сумму "
            + payout.getAmount()
            + " "
            + payout.getCurrency()
            + " была отправлена на ваш способ получения.",
        "/payment/payout",
        java.util.Map.of("payoutId", payout.getId()));
  }

  private void notifyPayoutBatchSent(PayoutBatch batch) {
    if (batch.getOwner() == null) return;
    notificationService.notify(
        batch.getOwner(),
        kz.hrms.splitupauth.entity.NotificationType.PAYOUT_SENT,
        "Выплата отправлена",
        "Выплата на сумму "
            + batch.getAmount()
            + " "
            + batch.getCurrency()
            + " была отправлена на ваш способ получения.",
        "/payment/payout",
        java.util.Map.of("payoutBatchId", batch.getId()));
  }

  private void appendPayoutSuccessLedger(Payout payout) {
    BigDecimal amount = payout.getSubmittedAmount() == null
        ? payout.getAmount() : payout.getSubmittedAmount();
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
    String status = payout.getStatus();
    boolean notYetPaid = "PENDING".equals(status) || "PENDING_METHOD".equals(status);

    if (notYetPaid && fullRefund) {
      String old = payout.getStatus();
      payout.setStatus("REVERSED");
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

    String status = payout.getStatus();
    boolean notYetDispatched =
        payout.getPayoutBatch() == null && payout.getSubmittedAmount() == null
            && ("PENDING".equals(status) || "PENDING_METHOD".equals(status) || "FROZEN".equals(status));
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
      BigDecimal submitted = payout.getSubmittedAmount() == null ? payout.getAmount() : payout.getSubmittedAmount();
      BigDecimal exposure = submitted.subtract(payable).max(BigDecimal.ZERO).setScale(2);
      payout.setClawbackAmount(exposure);
      payout.setClawbackRequired(exposure.signum() > 0);
      payoutRepository.save(payout);
      eventLogger.log("PAYOUT", payout.getId(), "CLAWBACK_REQUIRED", status, status,
          null, null, payout.getIdempotencyKey(), java.util.Map.of(
              "successfulRefundTotal", successfulRefundTotal.toPlainString(),
              "clawbackAmount", exposure.toPlainString()));
      return;
    }
    payout.setAmount(payable);
    if (payable.signum() == 0) {
      payout.setStatus("REVERSED");
      payout.setProcessedAt(LocalDateTime.now(clock));
      payout.setFailureReason("Reversed: successful refunds exhausted owner share");
    }
    payoutRepository.save(payout);
    eventLogger.log(
        "PAYOUT",
        payout.getId(),
        "REFUND_ADJUSTED",
        status,
        payout.getStatus(),
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
            user, PAYOUT_CURRENCY, HELD_STATUSES, calculatedAt);

    BigDecimal heldAmount =
        held.stream()
            .map(Payout::getAmount)
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
        .currency(PAYOUT_CURRENCY)
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

  @Transactional
  public PayoutMethod registerMethod(User user, String providerCardToken, String panMask) {
    if (providerCardToken == null || providerCardToken.isBlank()) {
      throw new InvalidRequestException("providerCardToken is required");
    }
    // Anti-IDOR: a payout method may only be registered from a card token the user
    // actually owns (one of their saved cards). Prevents registering someone else's
    // card token as a payout destination.
    savedCardRepository
        .findByUserAndProviderTokenAndProviderName(
            user, providerCardToken, FreedomPayGateway.PROVIDER_NAME)
        .filter(c -> c.getStatus() == SavedCardStatus.ACTIVE)
        .orElseThrow(
            () ->
                new InvalidRequestException(
                    "Card token does not belong to you or is not an active saved card"));

    // Idempotent: re-registering an already-connected card returns the existing method
    // (also avoids tripping the unique (user, provider_card_token) constraint).
    PayoutMethod already =
        payoutMethodRepository
            .findByUserAndProviderCardTokenAndStatus(user, providerCardToken, "ACTIVE")
            .orElse(null);
    if (already != null) {
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
            .status("ACTIVE")
            .build();
    return payoutMethodRepository.save(method);
  }

  @Transactional
  public PayoutMethod registerVerifiedPayoutMethod(
      User user, String providerCardToken, String panMask) {
    if (providerCardToken == null || providerCardToken.isBlank()) {
      throw new InvalidRequestException("providerCardToken is required");
    }
    PayoutMethod already =
        payoutMethodRepository
            .findByUserAndProviderCardTokenAndStatus(user, providerCardToken, "ACTIVE")
            .orElse(null);
    if (already != null) {
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
            .status("ACTIVE")
            .build();
    return payoutMethodRepository.save(method);
  }

  @Transactional(readOnly = true)
  public List<PayoutMethod> listMethods(User user) {
    return payoutMethodRepository.findByUserAndStatusOrderByIsDefaultDescCreatedAtDesc(
        user, "ACTIVE");
  }

  @Transactional
  public void revokeMethod(User user, Long id) {
    PayoutMethod method =
        payoutMethodRepository
            .findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Payout method not found"));
    if (!method.getUser().getId().equals(user.getId())) {
      throw new ForbiddenOperationException("Not your method");
    }
    method.setStatus("REVOKED");
    method.setIsDefault(false);
    method.setRevokedAt(LocalDateTime.now(clock));
    payoutMethodRepository.save(method);
  }

  private TransactionTemplate tx() {
    return new TransactionTemplate(transactionManager);
  }

  private BigDecimal payoutPayableAmount(Payout payout) {
    BigDecimal amount = payout.getPayableAmount() == null ? payout.getAmount() : payout.getPayableAmount();
    return amount == null ? BigDecimal.ZERO.setScale(2) : amount.setScale(2, RoundingMode.HALF_UP);
  }

  private String batchIdempotencyKey(List<Long> payoutIds) {
    String source = "payout-batch:" + payoutIds;
    UUID id = UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    return "payout-batch-" + id;
  }

  private String payoutProviderOrderId(Payout payout) {
    return payout.getProviderOrderId() == null ? String.valueOf(payout.getId()) : payout.getProviderOrderId();
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
      throw new IllegalStateException("Batch amount differs from frozen children: " + batch.getId());
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
      String providerOrderId) {}

  private record BatchDispatchClaim(
      Long batchId,
      String idempotencyKey,
      String destinationUserId,
      String destinationCardToken,
      BigDecimal amount,
      String currency,
      String providerOrderId) {}
}
