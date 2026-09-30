package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.CreateRefundRequest;
import kz.hrms.splitupauth.dto.RefundTransactionResponse;
import kz.hrms.splitupauth.dto.UpdateRefundStatusRequest;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceConflictException;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Refunds of captured charges.
 *
 * <p>Every path that creates or settles a refund first locks the parent
 * charge row, so the "refunds never exceed the capture" check and the parent
 * status update are race-free (the DB trigger {@code trg_refund_transactions_cap}
 * enforces the same cap as a backstop). Only SUCCESS refunds count as money
 * returned to the payer; PENDING ones merely reserve refundable amount.
 */
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

    private TransactionTemplate txTemplate;

    @Autowired
    void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /** Phase-1 result: either a replayed refund or the provider request to send. */
    private record RefundReservation(RefundTransactionResponse existing, Long refundId, GatewayRefundRequest request) {}

    /**
     * User-initiated refund request — owner of the original payment can request
     * a (partial) refund. The PENDING refund row is committed BEFORE the
     * provider is called, so a provider-side success followed by a crash leaves
     * a visible PENDING refund to reconcile instead of an invisible refund
     * that a retry could repeat.
     */
    public RefundTransactionResponse requestRefund(User currentUser, CreateRefundRequest request) {
        RefundReservation reservation = txTemplate.execute(s -> reserveUserRefund(currentUser, request));
        if (reservation.existing() != null) {
            return reservation.existing();
        }

        GatewayRefundResponse resp = null;
        try {
            resp = gatewayRegistry.defaultGateway().refund(reservation.request());
        } catch (Exception ex) {
            log.error("Refund call failed for {}: {}", reservation.refundId(), ex.getMessage());
            // Leave PENDING — admin can retry / reconcile.
        }

        final GatewayRefundResponse response = resp;
        return txTemplate.execute(s -> recordProviderRefundResult(reservation.refundId(), response));
    }

    private RefundReservation reserveUserRefund(User currentUser, CreateRefundRequest request) {
        PaymentTransaction tx = paymentTransactionRepository.findWithLockById(request.getPaymentTransactionId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment transaction not found"));

        // IDOR check: only the original payer can request a refund.
        if (tx.getPaymentIntent() == null
                || !tx.getPaymentIntent().getUser().getId().equals(currentUser.getId())) {
            throw new ForbiddenOperationException("Not your payment");
        }

        RefundTransaction existing = findReplay(request, tx);
        if (existing != null) {
            return new RefundReservation(map(existing), null, null);
        }

        ensureRefundable(tx);
        ensureWithinRemaining(tx, request.getAmount(), "REFUND_AMOUNT_EXCEEDED");

        RefundTransaction refund = RefundTransaction.builder()
                .paymentTransaction(tx)
                .status(RefundStatus.PENDING)
                .amount(request.getAmount())
                .currency(tx.getCurrency())
                .reason(request.getReason())
                .idempotencyKey(request.getIdempotencyKey())
                .build();
        refund = refundTransactionRepository.save(refund);

        eventLogger.log("REFUND", refund.getId(), "CREATED",
                null, refund.getStatus().name(),
                currentUser.getId(), null, refund.getIdempotencyKey(),
                java.util.Map.of("amount", refund.getAmount().toPlainString()));

        return new RefundReservation(null, refund.getId(), GatewayRefundRequest.builder()
                .refundId(refund.getId())
                .idempotencyKey(refund.getIdempotencyKey())
                .externalPaymentId(tx.getExternalTransactionId())
                .amount(refund.getAmount())
                .currency(refund.getCurrency())
                .reason(refund.getReason())
                .build());
    }

    private RefundTransactionResponse recordProviderRefundResult(Long refundId, GatewayRefundResponse resp) {
        RefundTransaction refund = refundTransactionRepository.findWithLockById(refundId)
                .orElseThrow(() -> new ResourceNotFoundException("Refund not found"));
        if (resp == null || refund.getStatus() != RefundStatus.PENDING) {
            return map(refund);
        }
        if (resp.isSuccess()) {
            refund.setStatus(RefundStatus.SUCCESS);
            refund.setProviderRefundId(resp.getExternalRefundId());
            refundTransactionRepository.save(refund);
            applyRefundToParentTransaction(refund);
        } else if (resp.isPending()) {
            refund.setProviderRefundId(resp.getExternalRefundId());
            // Stays PENDING; webhook or admin will finalize.
            refundTransactionRepository.save(refund);
        } else {
            refund.setStatus(RefundStatus.FAILED);
            refundTransactionRepository.save(refund);
        }
        return map(refund);
    }

    /**
     * Apply an async refund result callback from the provider. Finalizes a PENDING
     * refund (that the gateway accepted but hadn't settled) by its provider refund id.
     * Idempotent: ignores unknown or already-terminal refunds. Prod-only — the dev mock
     * settles refunds synchronously and never sends this callback.
     */
    @Transactional
    public void applyRefundWebhook(String providerRefundId, boolean success) {
        if (providerRefundId == null || providerRefundId.isBlank()) {
            log.warn("Refund webhook without provider refund id, ignoring");
            return;
        }
        RefundTransaction refund = refundTransactionRepository
                .findByProviderRefundId(providerRefundId).orElse(null);
        if (refund == null) {
            log.warn("Refund webhook references unknown provider refund id {}", providerRefundId);
            return;
        }
        if (refund.getStatus() != RefundStatus.PENDING) {
            return; // terminal — idempotent no-op
        }
        if (success) {
            refund.setStatus(RefundStatus.SUCCESS);
            refundTransactionRepository.save(refund);
            applyRefundToParentTransaction(refund);
        } else {
            refund.setStatus(RefundStatus.FAILED);
            refundTransactionRepository.save(refund);
        }
        eventLogger.log("REFUND", refund.getId(),
                success ? "WEBHOOK_SUCCESS" : "WEBHOOK_FAILED",
                "PENDING", refund.getStatus().name(),
                null, null, refund.getIdempotencyKey(), java.util.Map.of());
    }

    @Transactional(readOnly = true)
    public List<RefundTransactionResponse> listMine(User currentUser) {
        return refundTransactionRepository
                .findByPaymentTransaction_PaymentIntent_UserOrderByCreatedAtDesc(currentUser)
                .stream().map(this::map).toList();
    }

    @Transactional(readOnly = true)
    public RefundTransactionResponse getMine(User currentUser, Long refundId) {
        RefundTransaction refund = refundTransactionRepository.findById(refundId)
                .orElseThrow(() -> new ResourceNotFoundException("Refund not found"));
        boolean isOwner = refund.getPaymentTransaction().getPaymentIntent() != null
                && refund.getPaymentTransaction().getPaymentIntent().getUser().getId()
                        .equals(currentUser.getId());
        if (!isOwner && currentUser.getRole() != Role.ADMIN) {
            throw new ForbiddenOperationException("Not your refund");
        }
        return map(refund);
    }

    /**
     * Re-derives the parent charge status from SUCCESS refunds only and adjusts
     * the owner payable to the money the platform still retains. Locks the
     * parent row so concurrent settlements of sibling refunds serialize.
     */
    private void applyRefundToParentTransaction(RefundTransaction refund) {
        PaymentTransaction tx = paymentTransactionRepository.findWithLockById(refund.getPaymentTransaction().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment transaction not found"));
        BigDecimal refunded = refundTransactionRepository.sumSuccessfulRefundAmounts(tx);
        boolean fullRefund = refunded.compareTo(tx.getAmount()) >= 0;
        tx.setStatus(fullRefund ? PaymentTransactionStatus.REFUNDED_FULL : PaymentTransactionStatus.REFUNDED_PARTIAL);
        paymentTransactionRepository.save(tx);
        // Clawback: don't pay the owner for money that's been refunded.
        payoutService.adjustOwnerPayoutForRefund(tx.getPaymentIntent(), tx.getAmount().subtract(refunded));
    }

    @Transactional
    public RefundTransactionResponse createRefund(
            User currentUser,
            CreateRefundRequest request,
            HttpServletRequest httpRequest
    ) {
        ensureAdmin(currentUser);

        PaymentTransaction paymentTransaction = paymentTransactionRepository.findWithLockById(request.getPaymentTransactionId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment transaction not found"));

        RefundTransaction existing = findReplay(request, paymentTransaction);
        if (existing != null) {
            return map(existing);
        }

        if (paymentTransaction.getType() != PaymentTransactionType.CHARGE) {
            throw new InvalidRequestException("Refund can only be created for CHARGE transaction");
        }

        if (request.getAmount().compareTo(paymentTransaction.getAmount()) > 0) {
            throw new InvalidRequestException("Refund amount cannot exceed original payment amount");
        }
        ensureWithinRemaining(paymentTransaction, request.getAmount(), "Refund amount exceeds the remaining refundable amount");

        Dispute dispute = null;
        if (request.getDisputeId() != null) {
            dispute = disputeRepository.findById(request.getDisputeId())
                    .orElseThrow(() -> new ResourceNotFoundException("Dispute not found"));
        }

        RefundTransaction refund = RefundTransaction.builder()
                .paymentTransaction(paymentTransaction)
                .dispute(dispute)
                .adminUser(currentUser)
                .status(RefundStatus.PENDING)
                .amount(request.getAmount())
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
                        .build()
        );

        return map(refund);
    }

    @Transactional(readOnly = true)
    public List<RefundTransactionResponse> getRefundsByDispute(Long disputeId, User currentUser) {
        ensureAdmin(currentUser);

        Dispute dispute = disputeRepository.findById(disputeId)
                .orElseThrow(() -> new ResourceNotFoundException("Dispute not found"));

        return refundTransactionRepository.findByDisputeOrderByCreatedAtDesc(dispute)
                .stream()
                .map(this::map)
                .toList();
    }

    @Transactional
    public RefundTransactionResponse markSuccess(
            Long refundId,
            User currentUser,
            UpdateRefundStatusRequest request,
            HttpServletRequest httpRequest
    ) {
        ensureAdmin(currentUser);

        RefundTransaction refund = refundTransactionRepository.findWithLockById(refundId)
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
                        .build()
        );

        return map(refund);
    }

    @Transactional
    public RefundTransactionResponse markFailed(
            Long refundId,
            User currentUser,
            HttpServletRequest httpRequest
    ) {
        ensureAdmin(currentUser);

        RefundTransaction refund = refundTransactionRepository.findWithLockById(refundId)
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
                        .build()
        );

        return map(refund);
    }

    /**
     * Same key → same refund. Called with the parent charge locked, so a
     * concurrent retry waits and then sees the committed row. A key reused for
     * a different charge is a conflict, never a disclosure of that refund.
     */
    private RefundTransaction findReplay(CreateRefundRequest request, PaymentTransaction lockedTx) {
        RefundTransaction existing = refundTransactionRepository.findByIdempotencyKey(request.getIdempotencyKey())
                .orElse(null);
        if (existing != null && !existing.getPaymentTransaction().getId().equals(lockedTx.getId())) {
            throw new ResourceConflictException("IDEMPOTENCY_KEY_REUSED: this idempotency key belongs to a different refund");
        }
        return existing;
    }

    private void ensureRefundable(PaymentTransaction tx) {
        if (tx.getType() != PaymentTransactionType.CHARGE
                || (tx.getStatus() != PaymentTransactionStatus.SUCCESS
                    && tx.getStatus() != PaymentTransactionStatus.REFUNDED_PARTIAL)) {
            throw new InvalidRequestException("Only successful CHARGE can be refunded");
        }
    }

    /** Caller holds the parent row lock, so the remaining amount cannot change underneath. */
    private void ensureWithinRemaining(PaymentTransaction tx, BigDecimal amount, String messagePrefix) {
        BigDecimal remaining = tx.getAmount().subtract(refundTransactionRepository.sumActiveRefundAmounts(tx));
        if (amount.compareTo(remaining) > 0) {
            throw new InvalidRequestException(messagePrefix + ": " + remaining + " available");
        }
    }

    private void ensureAdmin(User currentUser) {
        if (currentUser == null || currentUser.getRole() != Role.ADMIN) {
            throw new ForbiddenOperationException("Admin access required");
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
