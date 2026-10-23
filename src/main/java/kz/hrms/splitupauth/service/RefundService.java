package kz.hrms.splitupauth.service;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import kz.hrms.splitupauth.dto.CreateRefundRequest;
import kz.hrms.splitupauth.dto.RefundTransactionResponse;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.payment.gateway.GatewayRefundRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayRefundResponse;
import kz.hrms.splitupauth.payment.gateway.GatewayRequestNotSentException;
import kz.hrms.splitupauth.payment.gateway.GatewayStatusResponse;
import kz.hrms.splitupauth.payment.gateway.PaymentGatewayRegistry;
import kz.hrms.splitupauth.payment.gateway.ProviderPaymentState;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.DisputeRepository;
import kz.hrms.splitupauth.repository.PaymentTransactionRepository;
import kz.hrms.splitupauth.repository.RefundTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class RefundService {

  private static final int REFUND_LEASE_MINUTES = 5;
  private static final int REFUND_REVIEW_AFTER_HOURS = 24;

  private final RefundTransactionRepository refundTransactionRepository;
  private final PaymentTransactionRepository paymentTransactionRepository;
  private final DisputeRepository disputeRepository;
  private final AdminActionLogRepository adminActionLogRepository;
  private final PaymentGatewayRegistry gatewayRegistry;
  private final PaymentEventLogger eventLogger;
  private final PayoutService payoutService;
  private final PayoutBlockService payoutBlockService;
  private final NotificationService notificationService;
  private final MoneyLedgerService moneyLedgerService;
  private final kz.hrms.splitupauth.payment.fiscal.FiscalReceiptBuilder fiscalReceiptBuilder;
  private final Clock clock;
  private final PlatformTransactionManager transactionManager;

  @Value("${app.money.refund-dispatch-enabled:false}")
  private boolean refundDispatchEnabled;

  /**
   * Max provider submission attempts for a temporary (not-sent / timeout) failure before FAILED.
   */
  @Value("${app.refund.max-attempts:8}")
  private int maxRefundAttempts = 8;

  /** Days quoted to the participant for a refund to arrive; surfaced to the UI. */
  @Value("${app.refund.expected-days:7}")
  private int refundExpectedDays = 7;

  /**
   * Apply an async refund result callback from the provider. Finalizes a PENDING refund (that the
   * gateway accepted but hadn't settled) by its provider refund id. Idempotent: ignores unknown or
   * already-terminal refunds. Prod-only: the dev mock settles refunds synchronously and never sends
   * this callback.
   */
  @Transactional
  public void applyRefundWebhook(String providerRefundId, boolean success) {
    if (providerRefundId == null || providerRefundId.isBlank()) {
      log.warn("Refund webhook without provider refund id, ignoring");
      return;
    }
    RefundTransaction refund =
        refundTransactionRepository.findWithLockByProviderRefundId(providerRefundId).orElse(null);
    if (refund == null) {
      throw new FreedomWebhookProcessingException(
          "REFUND_NOT_FOUND",
          "Webhook references unknown provider refund " + providerRefundId,
          true);
    }
    if (refund.getStatus() == RefundStatus.SUCCESS) {
      return; // terminal idempotent no-op
    }
    if (success) {
      refund.setStatus(RefundStatus.SUCCESS);
      refund.setLeaseUntil(null);
      refund.setNextRetryAt(null);
      refund.setLastErrorCode(null);
      refund.setLastErrorMessage(null);
      applyRefundToParentTransaction(refund);
      notifyRefundIssued(refund);
    } else {
      refund.setStatus(RefundStatus.REQUIRES_REVIEW);
      refund.setLeaseUntil(null);
      refund.setNextRetryAt(null);
      refund.setLastErrorCode("WEBHOOK_FAILED");
      refund.setLastErrorMessage("Provider reported refund failure");
      notifyRefundRequiresReview(refund);
    }
    refundTransactionRepository.save(refund);
    eventLogger.log(
        "REFUND",
        refund.getId(),
        success ? "WEBHOOK_SUCCESS" : "WEBHOOK_FAILED",
        "PENDING",
        refund.getStatus().name(),
        null,
        null,
        refund.getIdempotencyKey(),
        java.util.Map.of());
  }

  @Transactional(readOnly = true)
  public List<RefundTransactionResponse> listMine(User currentUser) {
    return refundTransactionRepository
        .findByPaymentTransaction_PaymentIntent_UserOrderByCreatedAtDesc(currentUser)
        .stream()
        .map(this::map)
        .toList();
  }

  @Transactional(readOnly = true)
  public RefundTransactionResponse getMine(User currentUser, Long refundId) {
    RefundTransaction refund =
        refundTransactionRepository
            .findWithLockById(refundId)
            .orElseThrow(() -> new ResourceNotFoundException("Refund not found"));
    boolean isOwner =
        refund.getPaymentTransaction().getPaymentIntent() != null
            && refund
                .getPaymentTransaction()
                .getPaymentIntent()
                .getUser()
                .getId()
                .equals(currentUser.getId());
    if (!isOwner && currentUser.getRole() != Role.ADMIN) {
      throw new ForbiddenOperationException("Not your refund");
    }
    return map(refund);
  }

  @Transactional
  public RefundTransaction createAutomaticCompensationRefund(
      PaymentTransaction transaction, String reason) {
    if (transaction == null || transaction.getId() == null) {
      throw new InvalidRequestException("Payment transaction is required for compensation refund");
    }
    if (transaction.getType() != PaymentTransactionType.CHARGE
        || transaction.getStatus() != PaymentTransactionStatus.SUCCESS) {
      throw new InvalidRequestException("Only successful CHARGE can be refunded");
    }

    String idempotencyKey = "compensation-refund-tx-" + transaction.getId();
    RefundTransaction existing =
        refundTransactionRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
    if (existing != null) {
      return existing;
    }

    RefundTransaction refund =
        RefundTransaction.builder()
            .paymentTransaction(transaction)
            .status(initialRefundStatus())
            .amount(transaction.getAmount())
            .currency(transaction.getCurrency())
            .reason("Automatic compensation: " + reason)
            .idempotencyKey(idempotencyKey)
            .build();
    refund = refundTransactionRepository.save(refund);

    eventLogger.log(
        "REFUND",
        refund.getId(),
        "AUTO_COMPENSATION_CREATED",
        null,
        refund.getStatus().name(),
        null,
        null,
        refund.getIdempotencyKey(),
        java.util.Map.of(
            "paymentTransactionId",
            String.valueOf(transaction.getId()),
            "reason",
            String.valueOf(reason)));

    scheduleRefundDispatchAfterCommit();
    return refund;
  }

  private void applyRefundToParentTransaction(RefundTransaction refund) {
    PaymentTransaction tx = refund.getPaymentTransaction();
    BigDecimal totalRefunded = refundTransactionRepository.sumSuccessfulRefundAmounts(tx);
    boolean fullRefund = totalRefunded.compareTo(tx.getAmount()) >= 0;
    tx.setStatus(
        fullRefund
            ? PaymentTransactionStatus.REFUNDED_FULL
            : PaymentTransactionStatus.REFUNDED_PARTIAL);
    paymentTransactionRepository.save(tx);
    moneyLedgerService.append(
        "REFUND",
        refund.getAmount(),
        refund.getCurrency(),
        "DEBIT",
        tx.getPaymentIntent(),
        tx,
        refund,
        null,
        tx.getRoom() == null ? null : tx.getRoom().getOwner(),
        "refund-" + refund.getId());
    PaymentIntent intent = tx.getPaymentIntent();
    if (fullRefund && intent != null && Boolean.TRUE.equals(intent.getCompensationRequired())) {
      intent.setStatus(PaymentIntentStatus.REFUNDED);
      intent.setReviewRequired(false);
      intent.setReviewReason(null);
    }
    if (fullRefund && tx.getRoomMember() != null) {
      RoomMember member = tx.getRoomMember();
      if (member.getStatus() == MemberStatus.PENDING || member.getStatus() == MemberStatus.ACTIVE) {
        member.setStatus(MemberStatus.CANCELLED_BEFORE_PAYMENT);
        member.setEndedAt(java.time.LocalDateTime.now());
      }
    }
    // Only provider-confirmed refunds alter the amount payable to the owner.
    payoutService.adjustOwnerPayoutForSuccessfulRefund(tx.getPaymentIntent(), totalRefunded);
    if (refund.getRefundRequest() != null) {
      payoutBlockService.releaseForPaymentIntent(
          tx.getPaymentIntent(),
          PayoutBlockSourceType.REFUND_REQUEST,
          refund.getRefundRequest().getId());
    }
  }

  @Transactional
  public RefundTransactionResponse createApprovedRefund(
      User currentUser,
      RefundRequest refundRequest,
      BigDecimal requestedAmount,
      String decisionNote,
      HttpServletRequest httpRequest) {
    ensureAdmin(currentUser);
    if (refundRequest == null || refundRequest.getId() == null) {
      throw new InvalidRequestException("Refund request is required");
    }

    String idempotencyKey = "approved-refund-request-" + refundRequest.getId();
    RefundTransaction existing =
        refundTransactionRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
    if (existing != null) {
      return map(existing);
    }

    PaymentTransaction paymentTransaction =
        paymentTransactionRepository
            .findWithLockById(refundRequest.getPaymentTransaction().getId())
            .orElseThrow(() -> new ResourceNotFoundException("Payment transaction not found"));
    if (paymentTransaction.getType() != PaymentTransactionType.CHARGE
        || (paymentTransaction.getStatus() != PaymentTransactionStatus.SUCCESS
            && paymentTransaction.getStatus() != PaymentTransactionStatus.REFUNDED_PARTIAL)) {
      throw new InvalidRequestException(
          "REFUND_NOT_CAPTURED", "Only a captured charge can be refunded");
    }

    BigDecimal already = refundTransactionRepository.sumActiveRefundAmounts(paymentTransaction);
    BigDecimal remaining = paymentTransaction.getAmount().subtract(already);
    if (remaining.signum() <= 0) {
      throw new InvalidRequestException(
          "REFUND_NO_BALANCE", "No captured balance remains to refund");
    }
    BigDecimal amount =
        requestedAmount == null ? remaining : normalizeRefundAmount(requestedAmount);
    if (amount.compareTo(remaining) > 0) {
      throw new InvalidRequestException(
          "REFUND_AMOUNT_EXCEEDS_BALANCE",
          "Refund amount cannot exceed available captured balance");
    }

    RefundTransaction refund =
        refundTransactionRepository.save(
            RefundTransaction.builder()
                .paymentTransaction(paymentTransaction)
                .refundRequest(refundRequest)
                .adminUser(currentUser)
                .status(initialRefundStatus())
                .amount(amount)
                .currency(paymentTransaction.getCurrency())
                .reason(decisionNote)
                .idempotencyKey(idempotencyKey)
                .build());

    adminActionLogRepository.save(
        AdminActionLog.builder()
            .eventId(UUID.randomUUID())
            .adminUser(currentUser)
            .actionType(AdminActionType.REFUND_INITIATED)
            .entityType("REFUND")
            .entityId(refund.getId())
            .reason(decisionNote)
            .ipAddress(httpRequest.getRemoteAddr())
            .userAgent(httpRequest.getHeader("User-Agent"))
            .build());

    scheduleRefundDispatchAfterCommit();
    return map(refund);
  }

  @Transactional
  public RefundTransactionResponse createRefund(
      User currentUser, CreateRefundRequest request, HttpServletRequest httpRequest) {
    ensureAdmin(currentUser);

    RefundTransaction existing =
        refundTransactionRepository.findByIdempotencyKey(request.getIdempotencyKey()).orElse(null);

    if (existing != null) {
      return map(existing);
    }

    PaymentTransaction paymentTransaction =
        paymentTransactionRepository
            .findWithLockById(request.getPaymentTransactionId())
            .orElseThrow(() -> new ResourceNotFoundException("Payment transaction not found"));

    if (paymentTransaction.getType() != PaymentTransactionType.CHARGE
        || (paymentTransaction.getStatus() != PaymentTransactionStatus.SUCCESS
            && paymentTransaction.getStatus() != PaymentTransactionStatus.REFUNDED_PARTIAL)) {
      throw new InvalidRequestException(
          "REFUND_NOT_CAPTURED", "Only a captured charge can be refunded");
    }

    BigDecimal already = refundTransactionRepository.sumActiveRefundAmounts(paymentTransaction);
    BigDecimal remaining = paymentTransaction.getAmount().subtract(already);
    if (remaining.signum() <= 0) {
      throw new InvalidRequestException(
          "REFUND_NO_BALANCE", "No captured balance remains to refund");
    }
    BigDecimal amount =
        request.getAmount() == null ? remaining : normalizeRefundAmount(request.getAmount());
    if (amount.compareTo(remaining) > 0) {
      throw new InvalidRequestException(
          "REFUND_AMOUNT_EXCEEDS_BALANCE",
          "Refund amount cannot exceed available captured balance");
    }

    Dispute dispute = null;
    if (request.getDisputeId() != null) {
      dispute =
          disputeRepository
              .findById(request.getDisputeId())
              .orElseThrow(() -> new ResourceNotFoundException("Dispute not found"));
    }

    RefundTransaction refund =
        RefundTransaction.builder()
            .paymentTransaction(paymentTransaction)
            .dispute(dispute)
            .adminUser(currentUser)
            .status(initialRefundStatus())
            .amount(amount)
            .currency(paymentTransaction.getCurrency())
            .reason(request.getReason())
            .idempotencyKey(request.getIdempotencyKey())
            .build();

    refund = refundTransactionRepository.save(refund);

    adminActionLogRepository.save(
        AdminActionLog.builder()
            .eventId(UUID.randomUUID())
            .adminUser(currentUser)
            .actionType(AdminActionType.REFUND_INITIATED)
            .entityType("REFUND")
            .entityId(refund.getId())
            .reason(request.getReason())
            .ipAddress(httpRequest.getRemoteAddr())
            .userAgent(httpRequest.getHeader("User-Agent"))
            .build());

    // A confirmed owner violation must actually reach the payment provider. The request is
    // persisted before dispatch so a process crash or transient gateway failure is retryable.
    scheduleRefundDispatchAfterCommit();

    return map(refund);
  }

  public void processPendingRefunds() {
    processPendingRefundsOnce(10);
  }

  public int processPendingRefundsOnce(int limit) {
    int processed = 0;
    while (processed < limit) {
      RefundDispatch dispatch = claimNextRefund();
      if (dispatch == null) {
        return processed;
      }
      dispatchRefund(dispatch);
      processed++;
    }
    return processed;
  }

  private RefundDispatch claimNextRefund() {
    return tx().execute(
            status -> {
              LocalDateTime now = LocalDateTime.now(clock);
              List<Long> dueIds =
                  refundTransactionRepository.findDispatchableIds(
                      now, maxRefundAttempts, PageRequest.of(0, 10));
              for (Long id : dueIds) {
                RefundTransaction refund =
                    refundTransactionRepository.findWithLockById(id).orElse(null);
                if (refund == null
                    || refund.getStatus() != RefundStatus.PENDING
                    || (refund.getLeaseUntil() != null && refund.getLeaseUntil().isAfter(now))) {
                  continue;
                }
                PaymentTransaction transaction = refund.getPaymentTransaction();
                // B6: fiscal receipt for the refund. The positions must sum to the refund amount or
                // the refund is not sent — it goes to review so finance can fix the fiscal config.
                List<kz.hrms.splitupauth.payment.fiscal.FiscalReceiptBuilder.ReceiptPosition>
                    positions = null;
                if (fiscalReceiptBuilder != null && fiscalReceiptBuilder.isEnabled()) {
                  positions = buildRefundReceiptPositions(refund, transaction);
                  if (!receiptPositionsMatch(positions, refund.getAmount())) {
                    refund.setStatus(RefundStatus.REQUIRES_REVIEW);
                    refund.setLeaseUntil(null);
                    refund.setNextRetryAt(null);
                    refund.setLastErrorCode("FISCAL_MISMATCH");
                    refund.setLastErrorMessage(
                        "Fiscal receipt positions do not sum to the refund amount");
                    refundTransactionRepository.save(refund);
                    notifyRefundRequiresReview(refund);
                    continue;
                  }
                }
                refund.setClaimedAt(now);
                refund.setLeaseUntil(now.plusMinutes(REFUND_LEASE_MINUTES));
                refundTransactionRepository.save(refund);
                // FreedomPay binds pg_idempotency_key to the first operation that used it and
                // refuses any later revoke with it (error 90271), so each attempt needs its own
                // key. Safe because an attempt is only retried after a definite rejection or a
                // request that was provably never sent; ambiguous ones are reconciled, not resent.
                String attemptKey =
                    "refund-" + refund.getId() + "-" + UUID.randomUUID().toString().substring(0, 8);
                return new RefundDispatch(
                    refund.getId(),
                    attemptKey,
                    transaction.getExternalTransactionId(),
                    refund.getAmount(),
                    refund.getCurrency(),
                    refund.getReason(),
                    positions);
              }
              return null;
            });
  }

  private void dispatchRefund(RefundDispatch refund) {
    try {
      var gateway = gatewayRegistry.defaultGateway();
      GatewayRefundResponse response =
          gateway.refund(
              GatewayRefundRequest.builder()
                  .refundId(refund.id())
                  .idempotencyKey(refund.idempotencyKey())
                  .externalPaymentId(refund.externalPaymentId())
                  .amount(refund.amount())
                  .currency(refund.currency())
                  .reason(refund.reason())
                  .receiptPositions(refund.receiptPositions())
                  .build());

      finalizeRefundDispatch(refund.id(), response, gateway.providerName());
    } catch (GatewayRequestNotSentException ex) {
      // Provably never reached the provider: safe to retry with the same idempotency key.
      log.warn("Refund {} not sent: {}", refund.id(), ex.getMessage());
      recordRefundDispatchFailure(refund.id(), ex);
    } catch (Exception ex) {
      // Timeout / unreadable / unsigned answer: the provider may have accepted the refund. A second
      // revoke could refund twice, so never resend — reconcile against the provider state instead.
      log.error("Refund {} submission ambiguous: {}", refund.id(), ex.getClass().getSimpleName());
      markRefundSubmissionAmbiguous(refund.id(), ex);
    }
  }

  private void markRefundSubmissionAmbiguous(Long refundId, Exception ex) {
    tx().executeWithoutResult(
            status -> {
              RefundTransaction refund =
                  refundTransactionRepository.findWithLockById(refundId).orElse(null);
              if (refund == null || refund.getStatus() != RefundStatus.PENDING) {
                return;
              }
              refund.setStatus(RefundStatus.PENDING_PROVIDER);
              refund.setProviderSubmittedAt(LocalDateTime.now(clock));
              refund.setLeaseUntil(null);
              refund.setNextRetryAt(null);
              refund.setLastErrorCode("SUBMISSION_AMBIGUOUS");
              refund.setLastErrorMessage(ex.getClass().getSimpleName());
              refundTransactionRepository.save(refund);
            });
  }

  /**
   * Settles PENDING_PROVIDER refunds from the provider's own payment state. FreedomPay's
   * revoke/cancel answer carries no refund id and no documented refund callback exists, so the
   * refunded total reported by get_status3 for the original payment is the source of truth: once it
   * covers every provider-confirmed refund of that charge plus this one, the refund succeeds. A
   * refund still not visible after {@code REFUND_REVIEW_AFTER_HOURS} goes to manual review. A
   * status query never re-sends anything. Returns the number of refunds examined.
   */
  public int reconcilePendingProviderRefunds(int limit) {
    LocalDateTime now = LocalDateTime.now(clock);
    List<Long> ids =
        tx().execute(
                status ->
                    refundTransactionRepository.findIdsPendingProviderForReconciliation(
                        now.minusMinutes(5), PageRequest.of(0, Math.max(1, limit))));
    int examined = 0;
    for (Long id : ids) {
      String externalPaymentId =
          tx().execute(
                  status -> {
                    RefundTransaction refund =
                        refundTransactionRepository.findWithLockById(id).orElse(null);
                    if (refund == null || refund.getStatus() != RefundStatus.PENDING_PROVIDER) {
                      return null;
                    }
                    refund.setLastReconciledAt(LocalDateTime.now(clock));
                    refundTransactionRepository.save(refund);
                    return refund.getPaymentTransaction().getExternalTransactionId();
                  });
      if (externalPaymentId == null || externalPaymentId.isBlank()) {
        continue;
      }
      examined++;
      GatewayStatusResponse providerStatus;
      try {
        providerStatus = gatewayRegistry.defaultGateway().getStatus(externalPaymentId);
      } catch (Exception ex) {
        log.warn("Refund {} reconciliation deferred: {}", id, ex.getClass().getSimpleName());
        continue;
      }
      tx().executeWithoutResult(status -> applyProviderRefundState(id, providerStatus));
    }
    return examined;
  }

  private void applyProviderRefundState(Long refundId, GatewayStatusResponse providerStatus) {
    RefundTransaction refund = refundTransactionRepository.findWithLockById(refundId).orElse(null);
    if (refund == null || refund.getStatus() != RefundStatus.PENDING_PROVIDER) {
      return;
    }
    PaymentTransaction charge = refund.getPaymentTransaction();
    // Baseline is the provider's own refunded total snapshotted when this refund was submitted; it
    // stays correct across several partial refunds even if our running sum diverges. Older rows
    // with
    // no snapshot fall back to our confirmed sum.
    BigDecimal baseline =
        refund.getProviderRefundedBefore() != null
            ? refund.getProviderRefundedBefore()
            : refundTransactionRepository.sumSuccessfulRefundAmounts(charge);
    BigDecimal expected = baseline.add(refund.getAmount());
    BigDecimal providerRefunded =
        providerStatus.getRefundedAmount() == null
            ? BigDecimal.ZERO
            : providerStatus.getRefundedAmount();
    boolean fullyReturned =
        providerStatus.getProviderState() == ProviderPaymentState.REFUNDED
            && expected.compareTo(charge.getAmount()) >= 0;
    if (fullyReturned || providerRefunded.compareTo(expected) >= 0) {
      refund.setStatus(RefundStatus.SUCCESS);
      refund.setLastErrorCode(null);
      refund.setLastErrorMessage(null);
      applyRefundToParentTransaction(refund);
      notifyRefundIssued(refund);
      refundTransactionRepository.save(refund);
      eventLogger.log(
          "REFUND",
          refund.getId(),
          "PROVIDER_RECONCILED_SUCCESS",
          RefundStatus.PENDING_PROVIDER.name(),
          refund.getStatus().name(),
          null,
          null,
          refund.getIdempotencyKey(),
          java.util.Map.of("providerRefunded", providerRefunded.toPlainString()));
      return;
    }
    LocalDateTime submitted =
        refund.getProviderSubmittedAt() == null
            ? refund.getCreatedAt()
            : refund.getProviderSubmittedAt();
    if (submitted != null
        && submitted.plusHours(REFUND_REVIEW_AFTER_HOURS).isBefore(LocalDateTime.now(clock))) {
      refund.setStatus(RefundStatus.REQUIRES_REVIEW);
      refund.setLastErrorCode("PROVIDER_REFUND_NOT_VISIBLE");
      refund.setLastErrorMessage(
          "Provider refunded total " + providerRefunded + " does not cover " + expected);
      refundTransactionRepository.save(refund);
      notifyRefundRequiresReview(refund);
    }
  }

  private void finalizeRefundDispatch(
      Long refundId, GatewayRefundResponse response, String providerName) {
    tx().executeWithoutResult(
            status -> {
              RefundTransaction refund =
                  refundTransactionRepository.findWithLockById(refundId).orElse(null);
              if (refund == null || refund.getStatus() != RefundStatus.PENDING) {
                return;
              }
              if (response.isSuccess()
                  && !FreedomPayGateway.PROVIDER_NAME.equalsIgnoreCase(providerName)) {
                refund.setStatus(RefundStatus.SUCCESS);
                refund.setProviderRefundId(response.getExternalRefundId());
                refund.setLeaseUntil(null);
                refund.setNextRetryAt(null);
                refund.setLastErrorCode(null);
                refund.setLastErrorMessage(null);
                applyRefundToParentTransaction(refund);
                notifyRefundIssued(refund);
              } else if (response.isPending() || response.isSuccess()) {
                refund.setStatus(RefundStatus.PENDING_PROVIDER);
                refund.setProviderSubmittedAt(LocalDateTime.now(clock));
                refund.setProviderRefundId(response.getExternalRefundId());
                if (response.getProviderRefundedBefore() != null) {
                  refund.setProviderRefundedBefore(response.getProviderRefundedBefore());
                }
                refund.setLeaseUntil(null);
                refund.setNextRetryAt(null);
              } else {
                // Keep the provider's own reason: without it a rejected refund cannot be diagnosed.
                String message =
                    "Provider rejected refund (status="
                        + response.getProviderStatusCode()
                        + ", code="
                        + response.getFailureCode()
                        + "): "
                        + response.getFailureMessage();
                log.warn("Refund {} rejected by provider: {}", refundId, message);
                if (isExceedError(response.getFailureCode(), response.getFailureMessage())) {
                  // Our ledger and the provider disagree on the refundable balance: never retry
                  // blindly, an operator must reconcile the amounts.
                  refund.setStatus(RefundStatus.REQUIRES_REVIEW);
                  refund.setLeaseUntil(null);
                  refund.setNextRetryAt(null);
                  refund.setLastErrorCode("REFUND_EXCEEDS_CAPTURED");
                  refund.setLastErrorMessage(message);
                  notifyRefundRequiresReview(refund);
                } else {
                  // A signed, definite rejection is permanent (timeouts/5xx throw and never reach
                  // here): fail it and page the admins rather than spin on pointless retries.
                  failRefund(refund, "PROVIDER_REJECTED", message);
                }
              }
              refundTransactionRepository.save(refund);
            });
  }

  private void recordRefundDispatchFailure(Long refundId, Exception ex) {
    tx().executeWithoutResult(
            status -> {
              RefundTransaction refund =
                  refundTransactionRepository.findWithLockById(refundId).orElse(null);
              if (refund == null || refund.getStatus() != RefundStatus.PENDING) {
                return;
              }
              scheduleRetryOrFail(refund, ex.getClass().getSimpleName(), ex.getMessage());
              refundTransactionRepository.save(refund);
            });
  }

  /**
   * A provably-not-sent (temporary) failure: retry with quadratic backoff up to {@code
   * app.refund.max-attempts}, then give up as FAILED and page the admins. Settled refunds never get
   * here; ambiguous submissions go to PENDING_PROVIDER reconciliation instead of retrying.
   */
  private void scheduleRetryOrFail(RefundTransaction refund, String code, String message) {
    int attempts = refund.getRetryCount() == null ? 0 : refund.getRetryCount();
    attempts++;
    refund.setRetryCount(attempts);
    refund.setLeaseUntil(null);
    refund.setLastErrorCode(code);
    refund.setLastErrorMessage(message);
    if (attempts >= maxRefundAttempts) {
      refund.setStatus(RefundStatus.FAILED);
      refund.setNextRetryAt(null);
      notifyRefundFailedAdmins(refund);
      return;
    }
    refund.setStatus(RefundStatus.PENDING);
    refund.setNextRetryAt(LocalDateTime.now(clock).plusMinutes((long) attempts * attempts));
  }

  /** Terminal failure of a refund we could not get the provider to accept; alerts the admins. */
  private void failRefund(RefundTransaction refund, String code, String message) {
    refund.setStatus(RefundStatus.FAILED);
    refund.setLeaseUntil(null);
    refund.setNextRetryAt(null);
    refund.setLastErrorCode(code);
    refund.setLastErrorMessage(message);
    notifyRefundFailedAdmins(refund);
  }

  /** FreedomPay rejected the revoke because the amount tops the captured/refundable balance. */
  private static boolean isExceedError(String code, String message) {
    String c = code == null ? "" : code.toLowerCase(Locale.ROOT);
    String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
    return c.contains("exceed")
        || c.contains("amount")
        || m.contains("exceed")
        || m.contains("превыш")
        || m.contains("больше суммы")
        || m.contains("exceeds");
  }

  private void scheduleRefundDispatchAfterCommit() {
    if (!refundDispatchEnabled || !TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            processPendingRefundsOnce(1);
          }
        });
  }

  /** One-shot admin alert that a refund is stuck in REQUIRES_REVIEW and needs an operator. */
  private void notifyRefundRequiresReview(RefundTransaction refund) {
    if (refund.getId() == null) {
      return;
    }
    notificationService.notifyAdminsOnce(
        NotificationType.REFUND_REQUIRES_REVIEW,
        refund.getId(),
        java.util.Map.of("entityId", String.valueOf(refund.getId())),
        "/admin/finance",
        java.util.Map.of("refundId", refund.getId()));
  }

  /** One-shot admin alert that a refund exhausted retries or was permanently rejected (FAILED). */
  private void notifyRefundFailedAdmins(RefundTransaction refund) {
    if (refund.getId() == null) {
      return;
    }
    notificationService.notifyAdminsOnce(
        NotificationType.REFUND_REQUIRES_REVIEW,
        refund.getId(),
        java.util.Map.of(
            "entityId",
            String.valueOf(refund.getId()),
            "reason",
            refund.getLastErrorCode() == null ? "FAILED" : refund.getLastErrorCode()),
        "/admin/finance",
        java.util.Map.of("refundId", refund.getId()));
  }

  private void notifyRefundIssued(RefundTransaction refund) {
    User recipient =
        refund.getPaymentTransaction() != null
                && refund.getPaymentTransaction().getPaymentIntent() != null
            ? refund.getPaymentTransaction().getPaymentIntent().getUser()
            : null;
    if (recipient == null) {
      return;
    }
    notificationService.notify(
        recipient,
        NotificationType.REFUND_ISSUED,
        java.util.Map.of(
            "amount",
            String.valueOf(refund.getAmount()),
            "currency",
            refund.getCurrency() == null ? "KZT" : refund.getCurrency()),
        "/payment/refund",
        java.util.Map.of("refundId", refund.getId()));
  }

  @Transactional(readOnly = true)
  public List<RefundTransactionResponse> getRefundsByDispute(Long disputeId, User currentUser) {
    ensureAdmin(currentUser);

    Dispute dispute =
        disputeRepository
            .findById(disputeId)
            .orElseThrow(() -> new ResourceNotFoundException("Dispute not found"));

    return refundTransactionRepository.findByDisputeOrderByCreatedAtDesc(dispute).stream()
        .map(this::map)
        .toList();
  }

  @Transactional
  public RefundTransactionResponse markFailed(
      Long refundId, User currentUser, HttpServletRequest httpRequest) {
    ensureAdmin(currentUser);

    RefundTransaction refund =
        refundTransactionRepository
            .findById(refundId)
            .orElseThrow(() -> new ResourceNotFoundException("Refund not found"));

    if (refund.getStatus() != RefundStatus.PENDING) {
      throw new InvalidRequestException("Only PENDING refund can be marked as failed");
    }

    refund.setStatus(RefundStatus.FAILED);
    refund.setAdminUser(currentUser);
    refundTransactionRepository.save(refund);

    adminActionLogRepository.save(
        AdminActionLog.builder()
            .eventId(UUID.randomUUID())
            .adminUser(currentUser)
            .actionType(AdminActionType.REFUND_REJECTED)
            .entityType("REFUND")
            .entityId(refund.getId())
            .reason(refund.getReason())
            .ipAddress(httpRequest.getRemoteAddr())
            .userAgent(httpRequest.getHeader("User-Agent"))
            .build());

    return map(refund);
  }

  /**
   * B8: admin re-queue of a stuck refund. A rejected/failed refund (REQUIRES_REVIEW / FAILED) is
   * re-dispatched — the gateway reads the provider state first and refuses an already-refunded
   * charge, so this cannot double-refund. A PENDING_PROVIDER refund is only nudged to reconcile
   * sooner (never re-sent). Terminal SUCCESS is a no-op.
   */
  @Transactional
  public void retryStuckRefund(Long id) {
    RefundTransaction refund =
        refundTransactionRepository
            .findWithLockById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Refund not found"));
    LocalDateTime now = LocalDateTime.now(clock);
    switch (refund.getStatus()) {
      case REQUIRES_REVIEW, FAILED -> {
        refund.setStatus(RefundStatus.PENDING);
        refund.setRetryCount(0);
        refund.setNextRetryAt(now);
        refund.setLeaseUntil(null);
        refundTransactionRepository.save(refund);
        scheduleRefundDispatchAfterCommit();
      }
      case PENDING_PROVIDER -> {
        refund.setNextRetryAt(now);
        refundTransactionRepository.save(refund);
      }
      case PENDING -> {
        refund.setNextRetryAt(now);
        refund.setLeaseUntil(null);
        refundTransactionRepository.save(refund);
      }
      default ->
          throw new InvalidRequestException(
              "REFUND_NOT_RETRYABLE", "Refund is not in a retryable state");
    }
    eventLogger.log(
        "REFUND",
        refund.getId(),
        "ADMIN_RETRY",
        null,
        refund.getStatus().name(),
        null,
        null,
        refund.getIdempotencyKey(),
        java.util.Map.of());
  }

  @Transactional(readOnly = true)
  public List<RefundTransaction> findStuck() {
    return refundTransactionRepository.findByStatusInOrderByCreatedAtAsc(
        List.of(RefundStatus.PENDING_PROVIDER, RefundStatus.REQUIRES_REVIEW, RefundStatus.FAILED));
  }

  /**
   * B5: with automatic dispatch off a refund is recorded as PENDING_MANUAL (not silently stuck in
   * PENDING), so the operator knows they must send it through the FreedomPay cabinet by hand.
   */
  private RefundStatus initialRefundStatus() {
    return refundDispatchEnabled ? RefundStatus.PENDING : RefundStatus.PENDING_MANUAL;
  }

  /**
   * B5: an operator confirms they refunded a PENDING_MANUAL refund by hand in the FreedomPay
   * cabinet. Records it as SUCCESS_MANUAL, applies it to the charge/ledger exactly like an
   * automatic success, and audits the admin action. Idempotent for an already-settled refund.
   */
  @Transactional
  public RefundTransactionResponse markRefundSentManually(
      Long refundId, User admin, String providerRef, String note, HttpServletRequest httpRequest) {
    ensureAdmin(admin);
    RefundTransaction refund =
        refundTransactionRepository
            .findWithLockById(refundId)
            .orElseThrow(() -> new ResourceNotFoundException("Refund not found"));
    if (refund.getStatus() == RefundStatus.SUCCESS
        || refund.getStatus() == RefundStatus.SUCCESS_MANUAL) {
      return map(refund); // idempotent
    }
    if (refund.getStatus() != RefundStatus.PENDING_MANUAL
        && refund.getStatus() != RefundStatus.PENDING
        && refund.getStatus() != RefundStatus.REQUIRES_REVIEW
        && refund.getStatus() != RefundStatus.FAILED) {
      throw new InvalidRequestException(
          "REFUND_NOT_MANUAL_SETTLEABLE", "Refund cannot be marked sent in its current state");
    }
    refund.setStatus(RefundStatus.SUCCESS_MANUAL);
    refund.setLeaseUntil(null);
    refund.setNextRetryAt(null);
    refund.setLastErrorCode(null);
    refund.setLastErrorMessage(null);
    if (notBlank(providerRef)) {
      refund.setProviderRefundId(providerRef);
    }
    refund.setAdminUser(admin);
    applyRefundToParentTransaction(refund);
    refundTransactionRepository.save(refund);

    adminActionLogRepository.save(
        AdminActionLog.builder()
            .eventId(UUID.randomUUID())
            .adminUser(admin)
            .actionType(AdminActionType.REFUND_APPROVED)
            .entityType("REFUND")
            .entityId(refund.getId())
            .reason(note)
            .ipAddress(httpRequest.getRemoteAddr())
            .userAgent(httpRequest.getHeader("User-Agent"))
            .build());

    notifyRefundIssued(refund);
    eventLogger.log(
        "REFUND",
        refund.getId(),
        "MARKED_SENT_MANUALLY",
        RefundStatus.PENDING_MANUAL.name(),
        refund.getStatus().name(),
        null,
        null,
        refund.getIdempotencyKey(),
        java.util.Map.of("providerRef", String.valueOf(providerRef)));
    return map(refund);
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private void ensureAdmin(User currentUser) {
    if (currentUser == null || currentUser.getRole() != Role.ADMIN) {
      throw new ForbiddenOperationException("Admin access required");
    }
  }

  private BigDecimal normalizeRefundAmount(BigDecimal amount) {
    if (amount == null || amount.signum() <= 0) {
      throw new InvalidRequestException(
          "REFUND_AMOUNT_INVALID", "Refund amount must be greater than zero");
    }
    try {
      return amount.setScale(2, java.math.RoundingMode.UNNECESSARY);
    } catch (ArithmeticException ex) {
      throw new InvalidRequestException("Refund amount must have at most 2 decimal places");
    }
  }

  private RefundTransactionResponse map(RefundTransaction refund) {
    return RefundTransactionResponse.builder()
        .id(refund.getId())
        .paymentTransactionId(refund.getPaymentTransaction().getId())
        .disputeId(refund.getDispute() != null ? refund.getDispute().getId() : null)
        .refundRequestId(
            refund.getRefundRequest() != null ? refund.getRefundRequest().getId() : null)
        .adminUserId(refund.getAdminUser() != null ? refund.getAdminUser().getId() : null)
        .status(refund.getStatus().name())
        .amount(refund.getAmount())
        .currency(refund.getCurrency())
        .reason(refund.getReason())
        .idempotencyKey(refund.getIdempotencyKey())
        .providerRefundId(refund.getProviderRefundId())
        .createdAt(refund.getCreatedAt())
        .updatedAt(refund.getUpdatedAt())
        .expectedDays(refundExpectedDays)
        .build();
  }

  private TransactionTemplate tx() {
    return new TransactionTemplate(transactionManager);
  }

  /**
   * B6: split the refund amount into its share and commission parts (proportional to the original
   * charge) and build the fiscal receipt positions. The split is exact — commission is rounded and
   * the share takes the remainder — so the positions always sum to the refund amount.
   */
  private List<kz.hrms.splitupauth.payment.fiscal.FiscalReceiptBuilder.ReceiptPosition>
      buildRefundReceiptPositions(RefundTransaction refund, PaymentTransaction charge) {
    BigDecimal amount = refund.getAmount();
    PaymentIntent intent = charge.getPaymentIntent();
    BigDecimal total = charge.getAmount();
    BigDecimal commission =
        intent == null || intent.getCommissionAmount() == null
            ? BigDecimal.ZERO
            : intent.getCommissionAmount();
    BigDecimal commissionRefund = BigDecimal.ZERO.setScale(2);
    if (commission.signum() > 0 && total != null && total.signum() > 0) {
      commissionRefund =
          amount.multiply(commission).divide(total, 2, java.math.RoundingMode.HALF_UP).min(amount);
    }
    BigDecimal shareRefund = amount.subtract(commissionRefund);
    return fiscalReceiptBuilder.forRefund(charge, shareRefund, commissionRefund);
  }

  private static boolean receiptPositionsMatch(
      List<kz.hrms.splitupauth.payment.fiscal.FiscalReceiptBuilder.ReceiptPosition> positions,
      BigDecimal amount) {
    if (positions == null || positions.isEmpty()) {
      return false;
    }
    BigDecimal sum =
        positions.stream()
            .map(kz.hrms.splitupauth.payment.fiscal.FiscalReceiptBuilder.ReceiptPosition::price)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return sum.compareTo(amount) == 0;
  }

  private record RefundDispatch(
      Long id,
      String idempotencyKey,
      String externalPaymentId,
      BigDecimal amount,
      String currency,
      String reason,
      List<kz.hrms.splitupauth.payment.fiscal.FiscalReceiptBuilder.ReceiptPosition>
          receiptPositions) {}
}
