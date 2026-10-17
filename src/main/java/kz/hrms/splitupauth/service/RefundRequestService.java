package kz.hrms.splitupauth.service;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kz.hrms.splitupauth.dto.ApproveRefundClaimRequest;
import kz.hrms.splitupauth.dto.CreateRefundClaimRequest;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.dto.RefundRequestResponse;
import kz.hrms.splitupauth.dto.RejectRefundClaimRequest;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.PaymentTransactionRepository;
import kz.hrms.splitupauth.repository.RefundRequestRepository;
import kz.hrms.splitupauth.repository.RefundTransactionRepository;
import kz.hrms.splitupauth.util.TextSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RefundRequestService {

  private static final int MAX_PAGE_SIZE = 100;

  private final RefundRequestRepository refundRequestRepository;
  private final PaymentTransactionRepository paymentTransactionRepository;
  private final PayoutBlockService payoutBlockService;
  private final RefundService refundService;
  private final RefundTransactionRepository refundTransactionRepository;
  private final AdminActionLogRepository adminActionLogRepository;
  private final NotificationService notificationService;
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

    // C2: block the owner payout for this intent while the claim is being reviewed; reject
    // releases the block, approve lets the refund itself cancel/claw back the payout.
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

  /**
   * C2: paginated admin list (default sort: newest first). {@code status} is optional. Mapped per
   * row to the enriched DTO so the admin table has room/member/amount context without an N+1 scan
   * on the frontend.
   */
  @Transactional(readOnly = true)
  public PagedResponse<RefundRequestResponse> listForAdmin(
      User currentUser, RefundRequestStatus status, int page, int size) {
    ensureAdmin(currentUser);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    Pageable pageable =
        PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
    Page<RefundRequest> result =
        status == null
            ? refundRequestRepository.findAll(pageable)
            : refundRequestRepository.findByStatus(status, pageable);
    List<RefundRequestResponse> items = result.getContent().stream().map(this::map).toList();
    return PagedResponse.<RefundRequestResponse>builder()
        .items(items)
        .page(result.getNumber())
        .size(result.getSize())
        .totalItems(result.getTotalElements())
        .totalPages(result.getTotalPages())
        .hasNext(result.hasNext())
        .hasPrevious(result.hasPrevious())
        .build();
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
    notifyRequesterApproved(claim, refund.getAmount(), refund.getCurrency());
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

    String note = TextSanitizer.sanitize(decision.getDecisionNote());
    claim.setStatus(RefundRequestStatus.REJECTED);
    claim.setDecisionNote(note);
    claim.setDecidedBy(currentUser);
    claim.setDecidedAt(LocalDateTime.now(clock));
    refundRequestRepository.save(claim);
    payoutBlockService.releaseForPaymentIntent(
        claim.getPaymentTransaction().getPaymentIntent(),
        PayoutBlockSourceType.REFUND_REQUEST,
        claim.getId());
    logDecision(claim, currentUser, AdminActionType.REFUND_REJECTED, httpRequest);
    notifyRequesterRejected(claim, note);
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

  private void notifyRequesterApproved(RefundRequest claim, BigDecimal amount, String currency) {
    Long roomId = roomIdOf(claim);
    notificationService.notify(
        claim.getRequester(),
        NotificationType.REFUND_ISSUED,
        Map.of(
            "amount", amount == null ? "" : amount.toPlainString(),
            "currency", currency == null ? "KZT" : currency),
        roomId != null ? "/rooms/member/" + roomId : null,
        null);
  }

  private void notifyRequesterRejected(RefundRequest claim, String reason) {
    Long roomId = roomIdOf(claim);
    notificationService.notify(
        claim.getRequester(),
        NotificationType.REFUND_REJECTED,
        Map.of("reason", reason == null ? "" : reason),
        roomId != null ? "/rooms/member/" + roomId : null,
        null);
  }

  private static Long roomIdOf(RefundRequest claim) {
    PaymentTransaction tx = claim.getPaymentTransaction();
    if (tx == null) return null;
    if (tx.getRoom() != null) return tx.getRoom().getId();
    if (tx.getRoomMember() != null && tx.getRoomMember().getRoom() != null)
      return tx.getRoomMember().getRoom().getId();
    return null;
  }

  private RefundRequestResponse map(RefundRequest request) {
    PaymentTransaction tx = request.getPaymentTransaction();
    Room room = tx != null ? tx.getRoom() : null;
    RoomMember member = tx != null ? tx.getRoomMember() : null;
    if (room == null && member != null) {
      room = member.getRoom();
    }

    BigDecimal charged = tx != null ? tx.getAmount() : null;
    BigDecimal refunded =
        tx != null ? refundTransactionRepository.sumActiveRefundAmounts(tx) : BigDecimal.ZERO;
    BigDecimal refundable =
        charged == null ? null : charged.subtract(refunded == null ? BigDecimal.ZERO : refunded);
    if (refundable != null && refundable.signum() < 0) {
      refundable = BigDecimal.ZERO;
    }

    User requester = request.getRequester();
    RefundRequestResponse.RequesterRef requesterRef =
        requester == null
            ? null
            : RefundRequestResponse.RequesterRef.builder()
                .id(requester.getId())
                .displayName(requester.getDisplayName())
                .publicId(requester.getPublicId())
                .build();

    return RefundRequestResponse.builder()
        .id(request.getId())
        .paymentTransactionId(tx != null ? tx.getId() : null)
        .reasonCode(request.getReasonCode())
        .description(request.getDescription())
        .status(request.getStatus())
        .approvedAmount(request.getApprovedAmount())
        .decisionNote(request.getDecisionNote())
        .createdAt(request.getCreatedAt())
        .updatedAt(request.getUpdatedAt())
        .decidedAt(request.getDecidedAt())
        .roomId(room != null ? room.getId() : null)
        .roomTitle(room != null ? room.getTitle() : null)
        .roomMemberId(member != null ? member.getId() : null)
        .requester(requesterRef)
        .chargedAmountKzt(charged)
        .refundedAmountKzt(refunded)
        .refundableAmountKzt(refundable)
        .build();
  }
}
