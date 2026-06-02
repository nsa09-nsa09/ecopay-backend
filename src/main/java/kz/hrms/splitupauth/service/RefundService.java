package kz.hrms.splitupauth.service;

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

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

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

    /**
     * User-initiated refund request — owner of the original payment can request
     * a (partial) refund. Calls the gateway synchronously.
     */
    @Transactional
    public RefundTransactionResponse requestRefund(User currentUser, CreateRefundRequest request) {
        RefundTransaction existing = refundTransactionRepository
                .findByIdempotencyKey(request.getIdempotencyKey()).orElse(null);
        if (existing != null) {
            return map(existing);
        }

        PaymentTransaction tx = paymentTransactionRepository.findById(request.getPaymentTransactionId())
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

        BigDecimal already = refundTransactionRepository.sumActiveRefundAmounts(tx);
        BigDecimal remaining = tx.getAmount().subtract(already);
        if (request.getAmount().compareTo(remaining) > 0) {
            throw new InvalidRequestException("REFUND_AMOUNT_EXCEEDED: " + remaining + " available");
        }

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

        try {
            GatewayRefundResponse resp = gatewayRegistry.defaultGateway().refund(
                    GatewayRefundRequest.builder()
                            .refundId(refund.getId())
                            .idempotencyKey(refund.getIdempotencyKey())
                            .externalPaymentId(tx.getExternalTransactionId())
                            .amount(refund.getAmount())
                            .currency(refund.getCurrency())
                            .reason(refund.getReason())
                            .build()
            );

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
            applyRefundToParentTransaction(refund);
        } else {
            refund.setStatus(RefundStatus.FAILED);
        }
        refundTransactionRepository.save(refund);
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

    private void applyRefundToParentTransaction(RefundTransaction refund) {
        PaymentTransaction tx = refund.getPaymentTransaction();
        BigDecimal totalRefunded = refundTransactionRepository.sumActiveRefundAmounts(tx);
        boolean fullRefund = totalRefunded.compareTo(tx.getAmount()) >= 0;
        tx.setStatus(fullRefund ? PaymentTransactionStatus.REFUNDED_FULL : PaymentTransactionStatus.REFUNDED_PARTIAL);
        paymentTransactionRepository.save(tx);
        // Clawback: don't pay the owner for money that's been refunded.
        payoutService.reverseOwnerPayoutForRefund(tx.getPaymentIntent(), fullRefund);
    }

    @Transactional
    public RefundTransactionResponse createRefund(
            User currentUser,
            CreateRefundRequest request,
            HttpServletRequest httpRequest
    ) {
        ensureAdmin(currentUser);

        RefundTransaction existing = refundTransactionRepository.findByIdempotencyKey(request.getIdempotencyKey())
                .orElse(null);

        if (existing != null) {
            return map(existing);
        }

        PaymentTransaction paymentTransaction = paymentTransactionRepository.findById(request.getPaymentTransactionId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment transaction not found"));

        if (paymentTransaction.getType() != PaymentTransactionType.CHARGE) {
            throw new InvalidRequestException("Refund can only be created for CHARGE transaction");
        }

        if (request.getAmount().compareTo(paymentTransaction.getAmount()) > 0) {
            throw new InvalidRequestException("Refund amount cannot exceed original payment amount");
        }

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

        RefundTransaction refund = refundTransactionRepository.findById(refundId)
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

        RefundTransaction refund = refundTransactionRepository.findById(refundId)
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