package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kz.hrms.splitupauth.dto.ApplyDisputeSanctionsRequest;
import kz.hrms.splitupauth.dto.CreateRefundRequest;
import kz.hrms.splitupauth.dto.CreateRoomComplaintRequest;
import kz.hrms.splitupauth.dto.DisputeDecisionRequest;
import kz.hrms.splitupauth.dto.DisputeResponse;
import kz.hrms.splitupauth.dto.PageResponse;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.*;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.util.TextSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DisputeService {

  private final DisputeRepository disputeRepository;
  private final SupportTicketRepository supportTicketRepository;
  private final AdminActionLogRepository adminActionLogRepository;
  private final RoomEventLogRepository roomEventLogRepository;
  private final RoomRepository roomRepository;
  private final RoomMemberRepository roomMemberRepository;
  private final PaymentTransactionRepository paymentTransactionRepository;
  private final RefundTransactionRepository refundTransactionRepository;
  private final UserRepository userRepository;
  private final RefundService refundService;
  private final ReputationService reputationService;
  private final NotificationService notificationService;
  private final PayoutBlockService payoutBlockService;

  /**
   * Opens an administrator-visible case directly from a paid room member. A report of an owner
   * breach must not depend on a support agent manually escalating a generic ticket first.
   */
  @Transactional
  public DisputeResponse openMemberComplaint(
      Long roomId, User currentUser, CreateRoomComplaintRequest request) {
    Room room =
        roomRepository
            .findById(roomId)
            .filter(r -> r.getDeletedAt() == null)
            .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

    RoomMember member =
        roomMemberRepository
            .findByRoomAndUserAndDeletedAtIsNull(room, currentUser)
            .orElseThrow(
                () -> new ForbiddenOperationException("You are not a member of this room"));

    if (member.getStatus() != MemberStatus.PENDING && member.getStatus() != MemberStatus.ACTIVE) {
      throw new InvalidRequestException("Only a paid active or pending membership can be reported");
    }
    if (!paymentTransactionRepository.existsByRoomMember_IdAndStatus(
        member.getId(), PaymentTransactionStatus.SUCCESS)) {
      throw new InvalidRequestException(
          "A successful payment is required before opening a complaint");
    }
    if (disputeRepository.existsByRoomMemberAndStatusIn(
        member, List.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW))) {
      throw new InvalidRequestException("There is already an open complaint for this membership");
    }

    // A newly opened case prevents automatic activation while administrators investigate it.
    member.setRequiresAdminReview(true);
    roomMemberRepository.save(member);

    Dispute dispute =
        Dispute.builder()
            .room(room)
            .roomMember(member)
            .openedByUser(currentUser)
            .reasonCode(request.getReasonCode().trim().toUpperCase())
            .description(TextSanitizer.sanitize(request.getDescription()))
            .status(DisputeStatus.OPEN)
            .build();
    dispute = disputeRepository.save(dispute);

    // B3: a dispute pauses the owner payout for the member's captured charge so money is not
    // moved while the complaint is being decided. The block is keyed on the dispute id so it can
    // be released cleanly when the admin resolves in favour of the owner, and remains in place for
    // a refund path.
    payoutBlockService.blockForRoomMember(
        member, PayoutBlockSourceType.DISPUTE, dispute.getId(), "DISPUTE");

    return map(dispute);
  }

  @Transactional
  public DisputeResponse openFromTicket(Long ticketId, User currentUser) {
    ensureStaff(currentUser);

    SupportTicket ticket =
        supportTicketRepository
            .findById(ticketId)
            .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));

    if (ticket.getRoom() == null) {
      throw new InvalidRequestException("Ticket must be linked to room to open dispute");
    }

    Dispute existing = disputeRepository.findByTicket(ticket).orElse(null);
    if (existing != null) {
      return map(existing);
    }

    Dispute dispute =
        Dispute.builder()
            .room(ticket.getRoom())
            .roomMember(ticket.getRoomMember())
            .ticket(ticket)
            .openedByUser(ticket.getUser())
            .assignedAdmin(currentUser.getRole() == Role.ADMIN ? currentUser : null)
            .reasonCode(ticket.getTopic())
            .description(ticket.getSubject())
            .status(DisputeStatus.OPEN)
            .build();

    dispute = disputeRepository.save(dispute);

    ticket.setEscalatedToDispute(true);
    ticket.setStatus(SupportTicketStatus.ESCALATED);
    if (currentUser.getRole() == Role.ADMIN) {
      ticket.setAssignedAdmin(currentUser);
    }
    supportTicketRepository.save(ticket);

    // B3: same as openMemberComplaint — hold the owner payout until the dispute is decided.
    if (ticket.getRoomMember() != null) {
      payoutBlockService.blockForRoomMember(
          ticket.getRoomMember(), PayoutBlockSourceType.DISPUTE, dispute.getId(), "DISPUTE");
    }

    return map(dispute);
  }

  @Transactional(readOnly = true)
  public List<DisputeResponse> getAdminQueue(User currentUser) {
    ensureAdmin(currentUser);

    return mapList(
        disputeRepository.findByStatusInOrderByCreatedAtAsc(
            List.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW)));
  }

  @Transactional(readOnly = true)
  public PageResponse<DisputeResponse> getAdminQueuePaged(User currentUser, int page, int size) {
    ensureAdmin(currentUser);

    Pageable pageable = PageRequest.of(page, size);

    Page<Dispute> result =
        disputeRepository.findByStatusInOrderByCreatedAtAsc(
            List.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW), pageable);

    List<DisputeResponse> items = mapList(result.getContent());

    return PageResponse.from(result, items);
  }

  @Transactional(readOnly = true)
  public DisputeResponse getAdminDispute(Long disputeId, User currentUser) {
    ensureAdmin(currentUser);

    Dispute dispute =
        disputeRepository
            .findById(disputeId)
            .orElseThrow(() -> new ResourceNotFoundException("Dispute not found"));

    return map(dispute);
  }

  @Transactional(readOnly = true)
  public List<DisputeResponse> getMyDisputes(User currentUser) {
    return mapList(disputeRepository.findByOpenedByUserOrderByCreatedAtDesc(currentUser));
  }

  @Transactional(readOnly = true)
  public DisputeResponse getMyDispute(Long disputeId, User currentUser) {
    Dispute dispute =
        disputeRepository
            .findByIdAndOpenedByUser(disputeId, currentUser)
            .orElseThrow(() -> new ResourceNotFoundException("Dispute not found"));

    return map(dispute);
  }

  @Transactional
  public DisputeResponse assignToMe(Long disputeId, User currentUser) {
    ensureAdmin(currentUser);

    Dispute dispute =
        disputeRepository
            .findById(disputeId)
            .orElseThrow(() -> new ResourceNotFoundException("Dispute not found"));

    if (dispute.getStatus() == DisputeStatus.RESOLVED
        || dispute.getStatus() == DisputeStatus.REJECTED) {
      throw new InvalidRequestException("Closed dispute cannot be assigned");
    }

    dispute.setAssignedAdmin(currentUser);
    dispute.setStatus(DisputeStatus.UNDER_REVIEW);

    disputeRepository.save(dispute);
    return map(dispute);
  }

  @Transactional
  public DisputeResponse decide(
      Long disputeId,
      User currentUser,
      DisputeDecisionRequest request,
      HttpServletRequest httpRequest) {
    ensureAdmin(currentUser);

    Dispute dispute =
        disputeRepository
            .findById(disputeId)
            .orElseThrow(() -> new ResourceNotFoundException("Dispute not found"));

    // A resolved/rejected dispute is terminal — cannot be re-decided.
    if (dispute.getStatus() == DisputeStatus.RESOLVED
        || dispute.getStatus() == DisputeStatus.REJECTED) {
      throw new InvalidRequestException("Dispute has already been decided");
    }

    DisputeStatus newStatus;
    try {
      newStatus = DisputeStatus.valueOf(request.getStatus().trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new InvalidRequestException("Unsupported dispute status");
    }

    if (newStatus != DisputeStatus.RESOLVED && newStatus != DisputeStatus.REJECTED) {
      throw new InvalidRequestException("Dispute can only be RESOLVED or REJECTED");
    }

    dispute.setAssignedAdmin(currentUser);
    dispute.setStatus(newStatus);
    dispute.setDecision(request.getDecision().trim());
    dispute.setDecisionComment(request.getComment().trim());
    dispute.setResolvedAt(LocalDateTime.now());

    disputeRepository.save(dispute);

    // B3: FAVOR_MEMBER (RESOLVED) with a paid member → issue a refund for the still-refundable
    // balance (or exactly `refundAmount` if the admin asked for a partial refund). The
    // underlying createRefund is idempotent (keyed on dispute+tx), honors the already-refunded
    // balance, and is a no-op if there is nothing left to return. On REJECTED (FAVOR_OWNER) the
    // payout block put in place when the dispute was opened is released so the money moves.
    boolean refundTriggered = false;
    RoomMember member = dispute.getRoomMember();
    if (newStatus == DisputeStatus.RESOLVED && member != null) {
      refundTriggered = createDisputeRefunds(currentUser, dispute, request, httpRequest);
    } else if (newStatus == DisputeStatus.REJECTED && member != null) {
      payoutBlockService.releaseForRoomMember(
          member, PayoutBlockSourceType.DISPUTE, dispute.getId());
    }

    adminActionLogRepository.save(
        AdminActionLog.builder()
            .eventId(UUID.randomUUID())
            .adminUser(currentUser)
            .actionType(AdminActionType.DISPUTE_RESOLVED)
            .entityType("DISPUTE")
            .entityId(dispute.getId())
            .reason(request.getComment())
            .ipAddress(httpRequest.getRemoteAddr())
            .userAgent(httpRequest.getHeader("User-Agent"))
            .build());

    if (dispute.getRoom() != null) {
      ObjectNode newState = JsonNodeFactory.instance.objectNode();
      newState.put("disputeStatus", dispute.getStatus().name());
      newState.put("decision", dispute.getDecision());
      newState.put("refundTriggered", refundTriggered);

      roomEventLogRepository.save(
          RoomEventLog.builder()
              .eventId(UUID.randomUUID())
              .actorUser(currentUser)
              .actorRole("ADMIN")
              .room(dispute.getRoom())
              .roomMember(dispute.getRoomMember())
              .eventType("DISPUTE_DECIDED")
              .newState(newState)
              .ipAddress(httpRequest.getRemoteAddr())
              .userAgent(httpRequest.getHeader("User-Agent"))
              .build());
    }

    // A decided dispute can confirm an owner violation, which penalises reputation.
    if (dispute.getRoom() != null) {
      reputationService.recompute(dispute.getRoom().getOwner());
    }

    // Notify whoever opened the dispute of the admin's decision. The old link ("/disputes-flows")
    // pointed at an internal dev page that returned 404 in production. Point the complainant at
    // the member view of the affected room so they can see the outcome in context, and also tell
    // the room owner so a payout release or refund does not come as a surprise.
    Long roomId = dispute.getRoom() != null ? dispute.getRoom().getId() : null;
    if (dispute.getOpenedByUser() != null && roomId != null) {
      notificationService.notify(
          dispute.getOpenedByUser(),
          NotificationType.DISPUTE_RESOLVED,
          java.util.Map.of(),
          "/rooms/member/" + roomId,
          java.util.Map.of(
              "disputeId",
              dispute.getId(),
              "status",
              dispute.getStatus().name(),
              "refundTriggered",
              refundTriggered));
    }
    if (dispute.getRoom() != null && dispute.getRoom().getOwner() != null && roomId != null) {
      notificationService.notify(
          dispute.getRoom().getOwner(),
          NotificationType.DISPUTE_RESOLVED,
          java.util.Map.of(),
          "/rooms/owner/" + roomId,
          java.util.Map.of(
              "disputeId",
              dispute.getId(),
              "status",
              dispute.getStatus().name(),
              "refundTriggered",
              refundTriggered));
    }

    return map(dispute, refundTriggered);
  }

  /**
   * Walk every captured charge the member has in this room and either refund the full remaining
   * balance (null {@code refundAmount}) or a specific amount applied across the charges in order
   * until the requested amount is exhausted. Returns true if at least one new refund was created.
   */
  private boolean createDisputeRefunds(
      User admin, Dispute dispute, DisputeDecisionRequest request, HttpServletRequest httpRequest) {
    RoomMember member = dispute.getRoomMember();
    List<PaymentTransaction> charges =
        paymentTransactionRepository.findByRoomMember_IdAndStatusAndTypeOrderByCreatedAtDesc(
            member.getId(), PaymentTransactionStatus.SUCCESS, PaymentTransactionType.CHARGE);
    if (charges.isEmpty()) {
      return false;
    }

    BigDecimal budget = request.getRefundAmount();
    boolean anyCreated = false;
    for (PaymentTransaction charge : charges) {
      BigDecimal remaining =
          charge.getAmount().subtract(refundTransactionRepository.sumActiveRefundAmounts(charge));
      if (remaining.signum() <= 0) continue;

      BigDecimal amount;
      if (budget == null) {
        amount = remaining;
      } else {
        if (budget.signum() <= 0) break;
        amount = budget.min(remaining);
      }

      CreateRefundRequest refundRequest = new CreateRefundRequest();
      refundRequest.setPaymentTransactionId(charge.getId());
      refundRequest.setDisputeId(dispute.getId());
      refundRequest.setAmount(amount);
      refundRequest.setReason(
          "Dispute #" + dispute.getId() + " resolved in member's favour: " + request.getComment());
      refundRequest.setIdempotencyKey("dispute-" + dispute.getId() + "-refund-" + charge.getId());
      refundService.createRefund(admin, refundRequest, httpRequest);
      anyCreated = true;
      if (budget != null) budget = budget.subtract(amount);
    }
    return anyCreated;
  }

  private void ensureAdmin(User currentUser) {
    if (currentUser == null || currentUser.getRole() != Role.ADMIN) {
      throw new ForbiddenOperationException("Admin access required");
    }
  }

  private void ensureStaff(User currentUser) {
    if (currentUser == null
        || (currentUser.getRole() != Role.ADMIN && currentUser.getRole() != Role.SUPPORT)) {
      throw new ForbiddenOperationException("Support or admin access required");
    }
  }

  @Transactional
  public DisputeResponse applyOwnerViolationSanctions(
      Long disputeId,
      User currentUser,
      ApplyDisputeSanctionsRequest request,
      jakarta.servlet.http.HttpServletRequest httpRequest) {
    ensureAdmin(currentUser);

    Dispute dispute =
        disputeRepository
            .findById(disputeId)
            .orElseThrow(() -> new ResourceNotFoundException("Dispute not found"));

    if (dispute.getStatus() == DisputeStatus.RESOLVED
        || dispute.getStatus() == DisputeStatus.REJECTED) {
      throw new InvalidRequestException("Closed dispute cannot receive owner-violation sanctions");
    }

    Room room = dispute.getRoom();
    if (room == null) {
      throw new InvalidRequestException("Dispute is not linked to room");
    }

    User owner = room.getOwner();
    if (owner == null) {
      throw new InvalidRequestException("Room owner not found");
    }

    room.setStatus(RoomStatus.BLOCKED);
    room.setBlockedAt(java.time.LocalDateTime.now());
    room.setBlockReason(request.getReason());
    roomRepository.save(room);

    owner.setStatus(UserStatus.BANNED);
    userRepository.save(owner);

    adminActionLogRepository.save(
        AdminActionLog.builder()
            .eventId(java.util.UUID.randomUUID())
            .adminUser(currentUser)
            .actionType(AdminActionType.ROOM_BLOCKED)
            .entityType("ROOM")
            .entityId(room.getId())
            .reason(request.getReason())
            .ipAddress(httpRequest.getRemoteAddr())
            .userAgent(httpRequest.getHeader("User-Agent"))
            .build());

    adminActionLogRepository.save(
        AdminActionLog.builder()
            .eventId(java.util.UUID.randomUUID())
            .adminUser(currentUser)
            .actionType(AdminActionType.USER_BANNED)
            .entityType("USER")
            .entityId(owner.getId())
            .reason(request.getReason())
            .ipAddress(httpRequest.getRemoteAddr())
            .userAgent(httpRequest.getHeader("User-Agent"))
            .build());

    com.fasterxml.jackson.databind.node.ObjectNode newState =
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    newState.put("roomStatus", "BLOCKED");
    newState.put("ownerStatus", "BANNED");
    newState.put("reason", request.getReason());

    roomEventLogRepository.save(
        RoomEventLog.builder()
            .eventId(java.util.UUID.randomUUID())
            .actorUser(currentUser)
            .actorRole("ADMIN")
            .room(room)
            .roomMember(dispute.getRoomMember())
            .eventType("OWNER_VIOLATION_SANCTIONED")
            .newState(newState)
            .ipAddress(httpRequest.getRemoteAddr())
            .userAgent(httpRequest.getHeader("User-Agent"))
            .build());

    // Once an owner breach is confirmed, every paid participant in the now-blocked room receives
    // their still-refundable captured payment. This avoids making an administrator copy payment
    // ids and amounts by hand, and makes duplicate clicks safe through the idempotency key.
    List<PaymentTransaction> capturedCharges =
        paymentTransactionRepository.findByRoom_IdAndStatusAndTypeOrderByCreatedAtAsc(
            room.getId(), PaymentTransactionStatus.SUCCESS, PaymentTransactionType.CHARGE);
    if (capturedCharges.isEmpty()) {
      throw new InvalidRequestException("No successful member payments are available for refund");
    }
    for (PaymentTransaction charge : capturedCharges) {
      var remaining =
          charge.getAmount().subtract(refundTransactionRepository.sumActiveRefundAmounts(charge));
      if (remaining.signum() <= 0) {
        continue;
      }
      CreateRefundRequest refundRequest = new CreateRefundRequest();
      refundRequest.setPaymentTransactionId(charge.getId());
      refundRequest.setDisputeId(dispute.getId());
      refundRequest.setAmount(remaining);
      refundRequest.setReason(request.getReason());
      refundRequest.setIdempotencyKey("dispute-" + dispute.getId() + "-refund-" + charge.getId());
      refundService.createRefund(currentUser, refundRequest, httpRequest);
    }

    dispute.setStatus(DisputeStatus.RESOLVED);
    dispute.setDecision("OWNER_VIOLATION_CONFIRMED");
    dispute.setDecisionComment(request.getReason());
    dispute.setResolvedAt(java.time.LocalDateTime.now());
    dispute.setAssignedAdmin(currentUser);
    disputeRepository.save(dispute);

    // Confirmed owner violation — refresh the owner's activity-based reputation.
    if (dispute.getRoom() != null) {
      reputationService.recompute(dispute.getRoom().getOwner());
    }

    return map(dispute);
  }

  /**
   * Single-dispute mapping. Loads the amounts for this one member — fine for show-one endpoints and
   * for the decide() flow. List endpoints batch amounts via {@link #mapList(java.util.List)}.
   */
  private DisputeResponse map(Dispute dispute) {
    return map(dispute, null);
  }

  private DisputeResponse map(Dispute dispute, Boolean overrideRefundTriggered) {
    Map<Long, BigDecimal> paid;
    Map<Long, BigDecimal> refunded;
    if (dispute.getRoomMember() != null) {
      Set<Long> ids = Set.of(dispute.getRoomMember().getId());
      paid = sumChargesByMember(ids);
      refunded = sumRefundsByMember(ids);
    } else {
      paid = Map.of();
      refunded = Map.of();
    }
    return buildResponse(dispute, paid, refunded, overrideRefundTriggered);
  }

  /** Batch mapping: one query for charges, one for refunds, then no per-dispute lookups. */
  private List<DisputeResponse> mapList(List<Dispute> disputes) {
    Set<Long> memberIds =
        disputes.stream()
            .map(d -> d.getRoomMember() != null ? d.getRoomMember().getId() : null)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
    Map<Long, BigDecimal> paid = sumChargesByMember(memberIds);
    Map<Long, BigDecimal> refunded = sumRefundsByMember(memberIds);
    return disputes.stream().map(d -> buildResponse(d, paid, refunded, null)).toList();
  }

  private Map<Long, BigDecimal> sumChargesByMember(Set<Long> ids) {
    if (ids.isEmpty()) return Map.of();
    Map<Long, BigDecimal> out = new HashMap<>();
    for (Object[] row : paymentTransactionRepository.sumSuccessChargesByRoomMemberIds(ids)) {
      out.put(((Number) row[0]).longValue(), (BigDecimal) row[1]);
    }
    return out;
  }

  private Map<Long, BigDecimal> sumRefundsByMember(Set<Long> ids) {
    if (ids.isEmpty()) return Map.of();
    Map<Long, BigDecimal> out = new HashMap<>();
    for (Object[] row : refundTransactionRepository.sumActiveRefundsByRoomMemberIds(ids)) {
      out.put(((Number) row[0]).longValue(), (BigDecimal) row[1]);
    }
    return out;
  }

  private DisputeResponse buildResponse(
      Dispute dispute,
      Map<Long, BigDecimal> paidByMember,
      Map<Long, BigDecimal> refundedByMember,
      Boolean overrideRefundTriggered) {
    Room room = dispute.getRoom();
    RoomMember member = dispute.getRoomMember();
    User owner = room != null ? room.getOwner() : null;

    BigDecimal paid =
        member != null
            ? paidByMember.getOrDefault(member.getId(), BigDecimal.ZERO)
            : BigDecimal.ZERO;
    BigDecimal refunded =
        member != null
            ? refundedByMember.getOrDefault(member.getId(), BigDecimal.ZERO)
            : BigDecimal.ZERO;
    BigDecimal refundable = paid.subtract(refunded).max(BigDecimal.ZERO);

    boolean refundTriggered;
    if (overrideRefundTriggered != null) {
      refundTriggered = overrideRefundTriggered;
    } else {
      refundTriggered = refunded.signum() > 0;
    }

    return DisputeResponse.builder()
        .id(dispute.getId())
        .roomId(room != null ? room.getId() : null)
        .roomTitle(room != null ? room.getTitle() : null)
        .serviceName(room != null && room.getService() != null ? room.getService().getName() : null)
        .roomMemberId(member != null ? member.getId() : null)
        .ticketId(dispute.getTicket() != null ? dispute.getTicket().getId() : null)
        .openedByUserId(dispute.getOpenedByUser().getId())
        .assignedAdminId(
            dispute.getAssignedAdmin() != null ? dispute.getAssignedAdmin().getId() : null)
        .reasonCode(dispute.getReasonCode())
        .description(dispute.getDescription())
        .status(dispute.getStatus().name())
        .decision(dispute.getDecision())
        .decisionComment(dispute.getDecisionComment())
        .resolvedAt(dispute.getResolvedAt())
        .createdAt(dispute.getCreatedAt())
        .updatedAt(dispute.getUpdatedAt())
        .owner(owner != null ? partyRef(owner) : null)
        .member(member != null ? memberRef(member) : null)
        .openedBy(dispute.getOpenedByUser() != null ? partyRef(dispute.getOpenedByUser()) : null)
        .paidAmountKzt(paid)
        .refundedAmountKzt(refunded)
        .refundableAmountKzt(refundable)
        .refundTriggered(refundTriggered)
        .build();
  }

  private DisputeResponse.PartyRef partyRef(User user) {
    return DisputeResponse.PartyRef.builder()
        .id(user.getId())
        .displayName(user.getDisplayName())
        .publicId(user.getPublicId())
        .build();
  }

  private DisputeResponse.MemberRef memberRef(RoomMember member) {
    User user = member.getUser();
    return DisputeResponse.MemberRef.builder()
        .id(member.getId())
        .userId(user != null ? user.getId() : null)
        .displayName(user != null ? user.getDisplayName() : null)
        .publicId(user != null ? user.getPublicId() : null)
        .status(member.getStatus() != null ? member.getStatus().name() : null)
        .build();
  }
}
