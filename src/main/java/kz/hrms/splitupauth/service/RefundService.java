package kz.hrms.splitupauth.service;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import kz.hrms.splitupauth.dto.CreateRefundRequest;
import kz.hrms.splitupauth.dto.RefundTransactionResponse;
import kz.hrms.splitupauth.dto.UpdateRefundStatusRequest;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.payment.gateway.GatewayRefundRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayRefundResponse;
import kz.hrms.splitupauth.payment.gateway.PaymentGatewayRegistry;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.DisputeRepository;
import kz.hrms.splitupauth.repository.PaymentTransactionRepository;
import kz.hrms.splitupauth.repository.RefundTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class RefundService {

  private final RefundTransactionRepository refundTransactionRepository;
  private final PaymentTransactionRepository paymentTransactionRepository;
  private final DisputeRepository disputeRepository;
  private final AdminActionLogRepository adminActionLogRepository;
  private final PaymentGatewayRegistry gatewayRegistry;
  private final PaymentEventLogger eventLogger;
  private final PayoutService payoutService;
  private final NotificationService notificationService;
  private final MoneyLedgerService moneyLedgerService;

  /**
   * User-initiated refund request — owner of the original payment can request a (partial) refund.
   * Calls the gateway synchronously.
   */
  @Transactional
  public RefundTransactionResponse requestRefund(User currentUser, CreateRefundRequest request) {
    RefundTransaction existing =
        refundTransactionRepository.findByIdempotencyKey(request.getIdempotencyKey()).orElse(null);
    if (existing != null) {
      return map(existing);
    }

    PaymentTransaction tx =
        paymentTransactionRepository
            .findWithLockById(request.getPaymentTransactionId())
            .orElseThrow(() -> new ResourceNotFoundException("Payment transaction not found"));

    // IDOR check: only the original payer can request a refund.
    if (tx.getPaymentIntent() == null
        || !tx.getPaymentIntent().getUser().getId().equals(currentUser.getId())) {
      throw new ForbiddenOperationException("Not your payment");
    }

    if (tx.getType() != PaymentTransactionType.CHARGE
        || tx.getStatus() != PaymentTransactionStatus.SUCCESS) {
      throw new InvalidRequestException("Only successful CHARGE can be refunded");
    }

    BigDecimal amount = normalizeRefundAmount(request.getAmount());
    BigDecimal already = refundTransactionRepository.sumActiveRefundAmounts(tx);
    BigDecimal remaining = tx.getAmount().subtract(already);
    if (amount.compareTo(remaining) > 0) {
      throw new InvalidRequestException("REFUND_AMOUNT_EXCEEDED: " + remaining + " available");
    }

    RefundTransaction refund =
        RefundTransaction.builder()
            .paymentTransaction(tx)
            .status(RefundStatus.PENDING)
            .amount(amount)
            .currency(tx.getCurrency())
            .reason(request.getReason())
            .idempotencyKey(request.getIdempotencyKey())
            .build();
    refund = refundTransactionRepository.save(refund);

    eventLogger.log(
        "REFUND",
        refund.getId(),
        "CREATED",
        null,
        refund.getStatus().name(),
        currentUser.getId(),
        null,
        refund.getIdempotencyKey(),
        java.util.Map.of("amount", refund.getAmount().toPlainString()));

    try {
      GatewayRefundResponse resp =
          gatewayRegistry
              .defaultGateway()
              .refund(
                  GatewayRefundRequest.builder()
                      .refundId(refund.getId())
                      .idempotencyKey(refund.getIdempotencyKey())
                      .externalPaymentId(tx.getExternalTransactionId())
                      .amount(refund.getAmount())
                      .currency(refund.getCurrency())
                      .reason(refund.getReason())
                      .build());

      if (resp.isSuccess()) {
        refund.setStatus(RefundStatus.SUCCESS);
        refund.setProviderRefundId(resp.getExternalRefundId());
        applyRefundToParentTransaction(refund);
      } else if (resp.isPending()) {
        refund.setProviderRefundId(resp.getExternalRefundId());
        // Stays PENDING; webhook or admin will finalize.
      } else {
        refund.setStatus(RefundStatus.FAILED);
      }
      refundTransactionRepository.save(refund);
    } catch (Exception ex) {
      log.error("Refund call failed for {}: {}", refund.getId(), ex.getMessage());
      // Leave PENDING — admin can retry.
    }

    return map(refund);
  }

  /**
   * Apply an async refund result callback from the provider. Finalizes a PENDING refund (that the
   * gateway accepted but hadn't settled) by its provider refund id. Idempotent: ignores unknown or
   * already-terminal refunds. Prod-only — the dev mock settles refunds synchronously and never
   * sends this callback.
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
    if (refund.getStatus() != RefundStatus.PENDING) {
      return; // terminal — idempotent no-op
    }
    if (success) {
      refund.setStatus(RefundStatus.SUCCESS);
      applyRefundToParentTransaction(refund);
      notifyRefundIssued(refund);
    } else {
      refund.setStatus(RefundStatus.FAILED);
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
            .status(RefundStatus.PENDING)
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

    dispatchRefund(refund);
    return refund;
  }

  private void applyRefundToParentTransaction(RefundTransaction refund) {
    PaymentTransaction tx = refund.getPaymentTransaction();
    BigDecimal totalRefunded = refundTransactionRepository.sumActiveRefundAmounts(tx);
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
    if (fullRefund
        && intent != null
        && Boolean.TRUE.equals(intent.getCompensationRequired())) {
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
    // Clawback: don't pay the owner for money that's been refunded.
    payoutService.reverseOwnerPayoutForRefund(tx.getPaymentIntent(), fullRefund);
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

    if (paymentTransaction.getType() != PaymentTransactionType.CHARGE) {
      throw new InvalidRequestException("Refund can only be created for CHARGE transaction");
    }

    BigDecimal amount = normalizeRefundAmount(request.getAmount());
    BigDecimal already = refundTransactionRepository.sumActiveRefundAmounts(paymentTransaction);
    BigDecimal remaining = paymentTransaction.getAmount().subtract(already);
    if (amount.compareTo(remaining) > 0) {
      throw new InvalidRequestException("Refund amount cannot exceed available captured balance");
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
            .status(RefundStatus.PENDING)
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

    // A confirmed owner violation must actually reach the payment provider. In production a
    // provider may acknowledge the request as PENDING and settle it via webhook; in dev the mock
    // settles synchronously and this immediately updates the membership and owner payout.
    dispatchRefund(refund);

    return map(refund);
  }

  private void dispatchRefund(RefundTransaction refund) {
    try {
      PaymentTransaction transaction = refund.getPaymentTransaction();
      GatewayRefundResponse response =
          gatewayRegistry
              .defaultGateway()
              .refund(
                  GatewayRefundRequest.builder()
                      .refundId(refund.getId())
                      .idempotencyKey(refund.getIdempotencyKey())
                      .externalPaymentId(transaction.getExternalTransactionId())
                      .amount(refund.getAmount())
                      .currency(refund.getCurrency())
                      .reason(refund.getReason())
                      .build());

      if (response.isSuccess()) {
        refund.setStatus(RefundStatus.SUCCESS);
        refund.setProviderRefundId(response.getExternalRefundId());
        applyRefundToParentTransaction(refund);
        notifyRefundIssued(refund);
      } else if (response.isPending()) {
        refund.setProviderRefundId(response.getExternalRefundId());
      } else {
        refund.setStatus(RefundStatus.FAILED);
      }
      refundTransactionRepository.save(refund);
    } catch (Exception ex) {
      log.error("Refund dispatch failed for {}: {}", refund.getId(), ex.getMessage());
      // Keep the persisted request PENDING for provider-webhook reconciliation or manual retry.
    }
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
        "Refund issued",
        "A refund of " + refund.getAmount() + " " + refund.getCurrency() + " has been issued.",
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
  public RefundTransactionResponse markSuccess(
      Long refundId,
      User currentUser,
      UpdateRefundStatusRequest request,
      HttpServletRequest httpRequest) {
    ensureAdmin(currentUser);

    RefundTransaction refund =
        refundTransactionRepository
            .findWithLockById(refundId)
            .orElseThrow(() -> new ResourceNotFoundException("Refund not found"));

    if (refund.getStatus() != RefundStatus.PENDING) {
      throw new InvalidRequestException("Only PENDING refund can be marked as success");
    }

    refund.setStatus(RefundStatus.SUCCESS);
    refund.setAdminUser(currentUser);
    refund.setProviderRefundId(request.getProviderRefundId());
    refundTransactionRepository.save(refund);

    // Mark the parent transaction refunded (full/partial) and reverse the owner payout
    // if it hasn't been paid out yet — centralized with the user/webhook refund paths.
    applyRefundToParentTransaction(refund);

    adminActionLogRepository.save(
        AdminActionLog.builder()
            .eventId(UUID.randomUUID())
            .adminUser(currentUser)
            .actionType(AdminActionType.REFUND_APPROVED)
            .entityType("REFUND")
            .entityId(refund.getId())
            .reason(refund.getReason())
            .ipAddress(httpRequest.getRemoteAddr())
            .userAgent(httpRequest.getHeader("User-Agent"))
            .build());

    // Notify the payer that their refund was issued.
    User recipient =
        refund.getPaymentTransaction() != null
                && refund.getPaymentTransaction().getPaymentIntent() != null
            ? refund.getPaymentTransaction().getPaymentIntent().getUser()
            : null;
    if (recipient != null) {
      notificationService.notify(
          recipient,
          NotificationType.REFUND_ISSUED,
          "Возврат средств",
          "Возврат на сумму " + refund.getAmount() + " " + refund.getCurrency() + " был выполнен.",
          "/payment/refund",
          java.util.Map.of("refundId", refund.getId()));
    }

    return map(refund);
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

  private void ensureAdmin(User currentUser) {
    if (currentUser == null || currentUser.getRole() != Role.ADMIN) {
      throw new ForbiddenOperationException("Admin access required");
    }
  }

  private BigDecimal normalizeRefundAmount(BigDecimal amount) {
    if (amount == null || amount.signum() <= 0) {
      throw new InvalidRequestException("Refund amount must be greater than zero");
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
        .adminUserId(refund.getAdminUser() != null ? refund.getAdminUser().getId() : null)
        .status(refund.getStatus().name())
        .amount(refund.getAmount())
        .currency(refund.getCurrency())
        .reason(refund.getReason())
        .idempotencyKey(refund.getIdempotencyKey())
        .providerRefundId(refund.getProviderRefundId())
        .createdAt(refund.getCreatedAt())
        .updatedAt(refund.getUpdatedAt())
        .build();
  }
}
