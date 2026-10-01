package kz.hrms.splitupauth.service;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import kz.hrms.splitupauth.dto.ApproveRefundClaimRequest;
import kz.hrms.splitupauth.dto.CreateRefundClaimRequest;
import kz.hrms.splitupauth.dto.RefundRequestResponse;
import kz.hrms.splitupauth.dto.RejectRefundClaimRequest;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.PaymentTransactionRepository;
import kz.hrms.splitupauth.repository.RefundRequestRepository;
import kz.hrms.splitupauth.util.TextSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RefundRequestService {

  private final RefundRequestRepository refundRequestRepository;
  private final PaymentTransactionRepository paymentTransactionRepository;
  private final PayoutBlockService payoutBlockService;
  private final RefundService refundService;
  private final AdminActionLogRepository adminActionLogRepository;
  private final Clock clock;

  @Transactional
  public RefundRequestResponse create(User currentUser, CreateRefundClaimRequest request) {
    RefundRequest existing =
        refundRequestRepository.findByIdempotencyKey(request.getIdempotencyKey()).orElse(null);
    if (existing != null) {
      if (!existing.getRequester().getId().equals(currentUser.getId())) {
        throw new ForbiddenOperationException("Idempotency key belongs to another user");
      }
      return map(existing);
    }

    PaymentTransaction transaction =
        paymentTransactionRepository
            .findWithLockById(request.getPaymentTransactionId())
            .orElseThrow(() -> new ResourceNotFoundException("Payment transaction not found"));
    PaymentIntent intent = transaction.getPaymentIntent();
    if (intent == null || !intent.getUser().getId().equals(currentUser.getId())) {
      throw new ForbiddenOperationException("Not your payment");
    }
    if (transaction.getType() != PaymentTransactionType.CHARGE
        || (transaction.getStatus() != PaymentTransactionStatus.SUCCESS
            && transaction.getStatus() != PaymentTransactionStatus.REFUNDED_PARTIAL)) {
      throw new InvalidRequestException("Only a captured charge can be disputed");
    }

    RefundRequest claim =
        refundRequestRepository.save(
            RefundRequest.builder()
                .paymentTransaction(transaction)
                .requester(currentUser)
                .reasonCode(request.getReasonCode())
                .description(TextSanitizer.sanitize(request.getDescription()))
                .status(RefundRequestStatus.REQUESTED)
                .idempotencyKey(request.getIdempotencyKey())
                .build());

    payoutBlockService.blockForPaymentIntent(
        intent,
        PayoutBlockSourceType.REFUND_REQUEST,
        claim.getId(),
        request.getReasonCode().name());
    return map(claim);
  }

  @Transactional(readOnly = true)
  public List<RefundRequestResponse> listMine(User currentUser) {
    return refundRequestRepository.findByRequesterOrderByCreatedAtDesc(currentUser).stream()
        .map(this::map)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<RefundRequestResponse> listForAdmin(User currentUser, RefundRequestStatus status) {
    ensureAdmin(currentUser);
    List<RefundRequest> requests =
        status == null
            ? refundRequestRepository.findAllByOrderByCreatedAtDesc()
            : refundRequestRepository.findByStatusOrderByCreatedAtAsc(status);
    return requests.stream().map(this::map).toList();
  }

  @Transactional
  public RefundRequestResponse approve(
      Long requestId,
      User currentUser,
      ApproveRefundClaimRequest decision,
      HttpServletRequest httpRequest) {
    ensureAdmin(currentUser);
    RefundRequest claim = getLocked(requestId);
    if (claim.getStatus() == RefundRequestStatus.APPROVED) {
      return map(claim);
    }
    if (claim.getStatus() == RefundRequestStatus.REJECTED) {
      throw new InvalidRequestException("Rejected refund request cannot be approved");
    }

    String note = TextSanitizer.sanitize(decision.getDecisionNote());
    var refund =
        refundService.createApprovedRefund(
            currentUser, claim, decision.getAmount(), note, httpRequest);

    claim.setStatus(RefundRequestStatus.APPROVED);
    claim.setApprovedAmount(refund.getAmount());
    claim.setDecisionNote(note);
    claim.setDecidedBy(currentUser);
    claim.setDecidedAt(LocalDateTime.now(clock));
    refundRequestRepository.save(claim);
    logDecision(claim, currentUser, AdminActionType.REFUND_APPROVED, httpRequest);
    return map(claim);
  }

  @Transactional
  public RefundRequestResponse reject(
      Long requestId,
      User currentUser,
      RejectRefundClaimRequest decision,
      HttpServletRequest httpRequest) {
    ensureAdmin(currentUser);
    RefundRequest claim = getLocked(requestId);
    if (claim.getStatus() == RefundRequestStatus.REJECTED) {
      return map(claim);
    }
    if (claim.getStatus() == RefundRequestStatus.APPROVED) {
      throw new InvalidRequestException("Approved refund request cannot be rejected");
    }

    claim.setStatus(RefundRequestStatus.REJECTED);
    claim.setDecisionNote(TextSanitizer.sanitize(decision.getDecisionNote()));
    claim.setDecidedBy(currentUser);
    claim.setDecidedAt(LocalDateTime.now(clock));
    refundRequestRepository.save(claim);
    payoutBlockService.releaseForPaymentIntent(
        claim.getPaymentTransaction().getPaymentIntent(),
        PayoutBlockSourceType.REFUND_REQUEST,
        claim.getId());
    logDecision(claim, currentUser, AdminActionType.REFUND_REJECTED, httpRequest);
    return map(claim);
  }

  private RefundRequest getLocked(Long requestId) {
    return refundRequestRepository
        .findWithLockById(requestId)
        .orElseThrow(() -> new ResourceNotFoundException("Refund request not found"));
  }

  private void ensureAdmin(User currentUser) {
    if (currentUser == null || currentUser.getRole() != Role.ADMIN) {
      throw new ForbiddenOperationException("Admin access required");
    }
  }

  private void logDecision(
      RefundRequest claim,
      User currentUser,
      AdminActionType actionType,
      HttpServletRequest httpRequest) {
    adminActionLogRepository.save(
        AdminActionLog.builder()
            .eventId(UUID.randomUUID())
            .adminUser(currentUser)
            .actionType(actionType)
            .entityType("REFUND_REQUEST")
            .entityId(claim.getId())
            .reason(claim.getDecisionNote())
            .ipAddress(httpRequest.getRemoteAddr())
            .userAgent(httpRequest.getHeader("User-Agent"))
            .build());
  }

  private RefundRequestResponse map(RefundRequest request) {
    return RefundRequestResponse.builder()
        .id(request.getId())
        .paymentTransactionId(request.getPaymentTransaction().getId())
        .reasonCode(request.getReasonCode())
        .description(request.getDescription())
        .status(request.getStatus())
        .approvedAmount(request.getApprovedAmount())
        .decisionNote(request.getDecisionNote())
        .createdAt(request.getCreatedAt())
        .updatedAt(request.getUpdatedAt())
        .decidedAt(request.getDecidedAt())
        .build();
  }
}
