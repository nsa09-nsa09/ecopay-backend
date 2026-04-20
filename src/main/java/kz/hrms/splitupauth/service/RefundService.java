package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.CreateRefundRequest;
import kz.hrms.splitupauth.dto.RefundTransactionResponse;
import kz.hrms.splitupauth.dto.UpdateRefundStatusRequest;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.DisputeRepository;
import kz.hrms.splitupauth.repository.PaymentTransactionRepository;
import kz.hrms.splitupauth.repository.RefundTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefundService {

    private final RefundTransactionRepository refundTransactionRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final DisputeRepository disputeRepository;
    private final AdminActionLogRepository adminActionLogRepository;

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

        PaymentTransaction paymentTransaction = refund.getPaymentTransaction();
        if (refund.getAmount().compareTo(paymentTransaction.getAmount()) >= 0) {
            paymentTransaction.setStatus(PaymentTransactionStatus.REFUNDED_FULL);
        } else {
            paymentTransaction.setStatus(PaymentTransactionStatus.REFUNDED_PARTIAL);
        }
        paymentTransactionRepository.save(paymentTransaction);

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