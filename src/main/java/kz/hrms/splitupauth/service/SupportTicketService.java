package kz.hrms.splitupauth.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import kz.hrms.splitupauth.dto.CreateSupportMessageRequest;
import kz.hrms.splitupauth.dto.CreateSupportTicketRequest;
import kz.hrms.splitupauth.dto.PageResponse;
import kz.hrms.splitupauth.dto.SupportMessageDto;
import kz.hrms.splitupauth.dto.SupportTicketResponse;
import kz.hrms.splitupauth.dto.UpdateSupportTicketStatusRequest;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.DisputeRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.SupportMessageRepository;
import kz.hrms.splitupauth.repository.SupportTicketRepository;
import kz.hrms.splitupauth.util.TextSanitizer;
import kz.hrms.splitupauth.websocket.SupportTicketRealtimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupportTicketService {

  private final SupportTicketRepository supportTicketRepository;
  private final SupportMessageRepository supportMessageRepository;
  private final RoomRepository roomRepository;
  private final RoomMemberRepository roomMemberRepository;
  private final ModerationService moderationService;
  private final SupportTicketRealtimeService supportTicketRealtimeService;
  private final NotificationService notificationService;
  private final PayoutBlockService payoutBlockService;
  private final DisputeRepository disputeRepository;

  @Transactional
  public SupportTicketResponse createTicket(User currentUser, CreateSupportTicketRequest request) {
    Room room = null;
    RoomMember roomMember = null;

    if (request.getRoomId() != null) {
      room =
          roomRepository
              .findById(request.getRoomId())
              .filter(r -> r.getDeletedAt() == null)
              .orElseThrow(() -> new ResourceNotFoundException("Room not found"));
    }

    if (request.getRoomMemberId() != null) {
      roomMember =
          roomMemberRepository
              .findById(request.getRoomMemberId())
              .filter(rm -> rm.getDeletedAt() == null)
              .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));

      if (!roomMember.getUser().getId().equals(currentUser.getId())
          && !roomMember.getRoom().getOwner().getId().equals(currentUser.getId())) {
        throw new ForbiddenOperationException("You cannot create ticket for this membership");
      }

      if (room == null) {
        room = roomMember.getRoom();
      } else if (!room.getId().equals(roomMember.getRoom().getId())) {
        // A ticket escalated to a dispute blocks payouts of its room, so the room must be the
        // membership's own room — never one chosen independently by the caller.
        throw new ForbiddenOperationException("Membership does not belong to this room");
      }
    }

    // IDOR guard: if a room is referenced without an (already-verified) membership,
    // the caller must be the owner or an actual member of that room.
    if (room != null && roomMember == null) {
      boolean isOwner = room.getOwner().getId().equals(currentUser.getId());
      RoomMember ownMembership =
          roomMemberRepository.findByRoomAndUserAndDeletedAtIsNull(room, currentUser).orElse(null);
      if (!isOwner && ownMembership == null) {
        throw new ForbiddenOperationException(
            "You cannot create a ticket for a room you are not part of");
      }
      // A member's ticket about their room concerns their own membership: link it so the ticket
      // blocks that membership's payout and can be escalated.
      if (!isOwner) {
        roomMember = ownMembership;
      }
    }

    SupportTicket ticket =
        SupportTicket.builder()
            .user(currentUser)
            .room(room)
            .roomMember(roomMember)
            .subject(TextSanitizer.sanitize(request.getSubject()))
            .topic(request.getTopic())
            .status(SupportTicketStatus.OPEN)
            .priority(
                roomMember != null ? SupportTicketPriority.HIGH : SupportTicketPriority.NORMAL)
            .escalatedToDispute(false)
            .build();

    ticket = supportTicketRepository.save(ticket);

    if (roomMember != null) {
      payoutBlockService.blockForRoomMember(
          roomMember, PayoutBlockSourceType.SUPPORT_TICKET, ticket.getId(), "SUPPORT_TICKET_OPEN");
    }

    SupportMessage firstMessage =
        SupportMessage.builder()
            .ticket(ticket)
            .senderUser(currentUser)
            .senderRole(SupportSenderRole.USER)
            .message(TextSanitizer.sanitize(request.getMessage()))
            .build();

    supportMessageRepository.save(firstMessage);

    if (roomMember != null) {
      if (!Boolean.TRUE.equals(roomMember.getRequiresAdminReview())) {
        roomMember.setRequiresAdminReview(true);
        roomMemberRepository.save(roomMember);
      }

      moderationService.enqueueMembershipForReview(roomMember, "SUPPORT_TICKET", BigDecimal.ZERO);
    }

    return publishAndReturn(ticket);
  }

  private SupportTicketResponse publishAndReturn(SupportTicket ticket) {
    SupportTicketResponse response = mapTicket(ticket);
    supportTicketRealtimeService.publishTicketUpdate(response);
    return response;
  }

  @Transactional(readOnly = true)
  public List<SupportTicketResponse> getMyTickets(User currentUser) {
    return supportTicketRepository.findByUserOrderByCreatedAtDesc(currentUser).stream()
        .map(this::mapTicket)
        .toList();
  }

  @Transactional(readOnly = true)
  public SupportTicketResponse getMyTicket(Long ticketId, User currentUser) {
    SupportTicket ticket =
        supportTicketRepository
            .findByIdAndUser(ticketId, currentUser)
            .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));

    return mapTicket(ticket);
  }

  @Transactional
  public SupportTicketResponse addMessage(
      Long ticketId, User currentUser, CreateSupportMessageRequest request) {
    SupportTicket ticket =
        supportTicketRepository
            .findById(ticketId)
            .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));

    if (!ticket.getUser().getId().equals(currentUser.getId())) {
      throw new ForbiddenOperationException("You can only write to your own ticket");
    }

    if (ticket.getStatus() == SupportTicketStatus.CLOSED) {
      throw new InvalidRequestException("Cannot write to closed ticket");
    }

    SupportMessage message =
        SupportMessage.builder()
            .ticket(ticket)
            .senderUser(currentUser)
            .senderRole(SupportSenderRole.USER)
            .message(TextSanitizer.sanitize(request.getMessage()))
            .build();

    supportMessageRepository.save(message);

    if (ticket.getStatus() == SupportTicketStatus.WAITING_USER) {
      ticket.setStatus(SupportTicketStatus.IN_PROGRESS);
      supportTicketRepository.save(ticket);
    }

    return publishAndReturn(ticket);
  }

  @Transactional(readOnly = true)
  public List<SupportTicketResponse> getStaffQueue(User currentUser) {
    ensureStaff(currentUser);

    return supportTicketRepository
        .findByStatusInOrderByCreatedAtAsc(
            List.of(
                SupportTicketStatus.OPEN,
                SupportTicketStatus.IN_PROGRESS,
                SupportTicketStatus.WAITING_USER,
                SupportTicketStatus.ESCALATED))
        .stream()
        .map(this::mapTicket)
        .toList();
  }

  /**
   * B8: paged staff queue with status + search filters. {@code statusFilter} accepts the string
   * aliases {@code ALL / ACTIVE / CLOSED} (case-insensitive) or a concrete {@link
   * SupportTicketStatus} name. {@code search} does a case-insensitive LIKE over subject + author
   * displayName + email, or an exact id match if the term parses as a number. Sort order is
   * ascending by createdAt for ACTIVE / OPEN-ish filters, descending by updatedAt for CLOSED and
   * for the catch-all ALL.
   */
  @Transactional(readOnly = true)
  public PageResponse<SupportTicketResponse> getStaffQueuePaged(
      User currentUser, String statusFilter, String search, int page, int size) {
    ensureStaff(currentUser);

    if (page < 0) page = 0;
    if (size <= 0 || size > 100) size = 20;

    QueueFilter filter = resolveFilter(statusFilter);
    Pageable pageable =
        PageRequest.of(
            page,
            size,
            filter.descendingByUpdatedAt
                ? org.springframework.data.domain.Sort.by(
                    org.springframework.data.domain.Sort.Direction.DESC, "updatedAt")
                : org.springframework.data.domain.Sort.by(
                    org.springframework.data.domain.Sort.Direction.ASC, "createdAt"));

    org.springframework.data.jpa.domain.Specification<SupportTicket> spec =
        (root, q, cb) -> cb.conjunction();
    if (filter.statuses != null) {
      final List<SupportTicketStatus> ss = filter.statuses;
      spec = spec.and((root, q, cb) -> root.get("status").in(ss));
    }
    if (search != null && !search.isBlank()) {
      final String trimmed = search.trim();
      final String like = "%" + trimmed.toLowerCase() + "%";
      final Long asId = parseLongOrNull(trimmed);
      spec =
          spec.and(
              (root, q, cb) -> {
                var subjectPred = cb.like(cb.lower(root.get("subject")), like);
                var userJoin = root.<SupportTicket, User>join("user");
                var namePred = cb.like(cb.lower(userJoin.get("displayName")), like);
                var emailPred = cb.like(cb.lower(cb.coalesce(userJoin.get("email"), "")), like);
                if (asId != null) {
                  return cb.or(subjectPred, namePred, emailPred, cb.equal(root.get("id"), asId));
                }
                return cb.or(subjectPred, namePred, emailPred);
              });
    }

    Page<SupportTicket> result = supportTicketRepository.findAll(spec, pageable);
    List<SupportTicketResponse> items = result.getContent().stream().map(this::mapTicket).toList();
    return PageResponse.from(result, items);
  }

  /** Backward-compatible overload — defaults to ACTIVE / no search. */
  @Transactional(readOnly = true)
  public PageResponse<SupportTicketResponse> getStaffQueuePaged(
      User currentUser, int page, int size) {
    return getStaffQueuePaged(currentUser, "ACTIVE", null, page, size);
  }

  private static final List<SupportTicketStatus> ACTIVE_STATUSES =
      List.of(
          SupportTicketStatus.OPEN,
          SupportTicketStatus.IN_PROGRESS,
          SupportTicketStatus.WAITING_USER,
          SupportTicketStatus.ESCALATED);

  /**
   * Filter + sort choice for the staff queue. {@code statuses == null} means "every status" (the
   * ALL alias). {@code descendingByUpdatedAt} swaps the default createdAt-asc sort for the closed /
   * all views, mirroring the spec's expectation.
   */
  private static final class QueueFilter {
    final List<SupportTicketStatus> statuses;
    final boolean descendingByUpdatedAt;

    QueueFilter(List<SupportTicketStatus> statuses, boolean descendingByUpdatedAt) {
      this.statuses = statuses;
      this.descendingByUpdatedAt = descendingByUpdatedAt;
    }
  }

  private QueueFilter resolveFilter(String s) {
    if (s == null || s.isBlank()) return new QueueFilter(ACTIVE_STATUSES, false);
    String token = s.trim().toUpperCase();
    switch (token) {
      case "ALL":
        return new QueueFilter(null, true);
      case "CLOSED":
        return new QueueFilter(List.of(SupportTicketStatus.CLOSED), true);
      case "ACTIVE":
        return new QueueFilter(ACTIVE_STATUSES, false);
      default:
        try {
          SupportTicketStatus concrete = SupportTicketStatus.valueOf(token);
          boolean closedLike = concrete == SupportTicketStatus.CLOSED;
          return new QueueFilter(List.of(concrete), closedLike);
        } catch (IllegalArgumentException ex) {
          return new QueueFilter(ACTIVE_STATUSES, false);
        }
    }
  }

  private static Long parseLongOrNull(String s) {
    try {
      return Long.parseLong(s.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  @Transactional(readOnly = true)
  public List<SupportTicketResponse> getAssignedTickets(User currentUser) {
    ensureStaff(currentUser);

    return supportTicketRepository
        .findByAssignedAdminOrderByUpdatedAtDescCreatedAtDesc(currentUser)
        .stream()
        .map(this::mapTicket)
        .toList();
  }

  // Get assigned tickets with pagination
  @Transactional(readOnly = true)
  public PageResponse<SupportTicketResponse> getAssignedTicketsPaged(
      User currentUser, int page, int size) {
    ensureStaff(currentUser);

    Pageable pageable = PageRequest.of(page, size);

    Page<SupportTicket> result =
        supportTicketRepository.findByAssignedAdminOrderByUpdatedAtDescCreatedAtDesc(
            currentUser, pageable);

    List<SupportTicketResponse> items = result.getContent().stream().map(this::mapTicket).toList();

    return PageResponse.from(result, items);
  }

  @Transactional(readOnly = true)
  public SupportTicketResponse getStaffTicket(Long ticketId, User currentUser) {
    ensureStaff(currentUser);

    SupportTicket ticket =
        supportTicketRepository
            .findById(ticketId)
            .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));

    return mapTicket(ticket);
  }

  @Transactional
  public SupportTicketResponse assignTicketToMe(Long ticketId, User currentUser) {
    ensureStaff(currentUser);

    SupportTicket ticket =
        supportTicketRepository
            .findById(ticketId)
            .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));

    if (ticket.getStatus() == SupportTicketStatus.CLOSED) {
      throw new InvalidRequestException("Cannot assign closed ticket");
    }

    ticket.setAssignedAdmin(currentUser);

    if (ticket.getStatus() == SupportTicketStatus.OPEN) {
      ticket.setStatus(SupportTicketStatus.IN_PROGRESS);
    }

    supportTicketRepository.save(ticket);
    return publishAndReturn(ticket);
  }

  @Transactional
  public SupportTicketResponse updateTicketStatus(
      Long ticketId, User currentUser, UpdateSupportTicketStatusRequest request) {
    ensureStaff(currentUser);

    SupportTicket ticket =
        supportTicketRepository
            .findById(ticketId)
            .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));

    SupportTicketStatus newStatus;
    try {
      newStatus = SupportTicketStatus.valueOf(request.getStatus().trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new InvalidRequestException("Unsupported ticket status");
    }

    if (newStatus == SupportTicketStatus.ESCALATED) {
      throw new InvalidRequestException("Use dedicated escalate endpoint");
    }

    ticket.setAssignedAdmin(currentUser);
    ticket.setStatus(newStatus);

    if (newStatus == SupportTicketStatus.CLOSED) {
      ticket.setClosedAt(LocalDateTime.now());
    } else {
      ticket.setClosedAt(null);
    }

    supportTicketRepository.save(ticket);
    return publishAndReturn(ticket);
  }

  @Transactional
  public SupportTicketResponse addStaffMessage(
      Long ticketId, User currentUser, CreateSupportMessageRequest request) {
    ensureStaff(currentUser);

    SupportTicket ticket =
        supportTicketRepository
            .findById(ticketId)
            .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));

    if (ticket.getStatus() == SupportTicketStatus.CLOSED) {
      throw new InvalidRequestException("Cannot write to closed ticket");
    }

    ticket.setAssignedAdmin(currentUser);

    SupportMessage message =
        SupportMessage.builder()
            .ticket(ticket)
            .senderUser(currentUser)
            .senderRole(resolveStaffSenderRole(currentUser))
            .message(request.getMessage())
            .build();

    supportMessageRepository.save(message);

    if (ticket.getStatus() == SupportTicketStatus.OPEN
        || ticket.getStatus() == SupportTicketStatus.IN_PROGRESS
        || ticket.getStatus() == SupportTicketStatus.WAITING_USER) {
      ticket.setStatus(SupportTicketStatus.WAITING_USER);
    }

    supportTicketRepository.save(ticket);

    // Notify the ticket owner that support replied.
    notificationService.notify(
        ticket.getUser(),
        NotificationType.TICKET_REPLY,
        java.util.Map.of(),
        "/support",
        java.util.Map.of("ticketId", ticket.getId()));

    return publishAndReturn(ticket);
  }

  @Transactional
  public SupportTicketResponse escalateTicketToAdmin(Long ticketId, User currentUser) {
    ensureStaff(currentUser);

    SupportTicket ticket =
        supportTicketRepository
            .findById(ticketId)
            .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));

    if (ticket.getStatus() == SupportTicketStatus.CLOSED) {
      throw new InvalidRequestException("Cannot escalate closed ticket");
    }

    // B2: before the fix the escalate button only set requires_admin_review and enqueued
    // moderation;
    // no Dispute was ever persisted, so /admin/disputes stayed empty while the admin UI thought the
    // case had been escalated. Now a Dispute is created for every escalate so the admin queue sees
    // it. The room link is required (a Dispute is a room-scoped case); the membership link is not
    // (owner-only reports, chat issues on a shared room, etc. still produce a dispute).
    if (ticket.getRoom() == null) {
      throw new InvalidRequestException(
          "TICKET_NOT_LINKED_TO_ROOM",
          "Чтобы открыть спор, заявка должна быть привязана к комнате");
    }

    RoomMember roomMember = ticket.getRoomMember();

    // Moderation queue + requiresAdminReview still only applies when there is a specific
    // membership to review; owner-only tickets go straight to the dispute queue.
    if (roomMember != null) {
      if (!Boolean.TRUE.equals(roomMember.getRequiresAdminReview())) {
        roomMember.setRequiresAdminReview(true);
        roomMemberRepository.save(roomMember);
      }
      moderationService.enqueueMembershipForReview(
          roomMember, "SUPPORT_ESCALATION", BigDecimal.ZERO);
    }

    Dispute existingDispute = disputeRepository.findByTicket(ticket).orElse(null);
    Dispute dispute;
    if (existingDispute != null) {
      dispute = existingDispute;
    } else {
      dispute =
          disputeRepository.save(
              Dispute.builder()
                  .room(ticket.getRoom())
                  .roomMember(roomMember)
                  .ticket(ticket)
                  .openedByUser(ticket.getUser())
                  .assignedAdmin(currentUser.getRole() == Role.ADMIN ? currentUser : null)
                  .reasonCode(ticket.getTopic() != null ? ticket.getTopic() : "SUPPORT_TICKET")
                  .description(ticket.getSubject())
                  .status(DisputeStatus.OPEN)
                  .build());

      // B3: new dispute → freeze the owner's payout for this membership (if any) until it is
      // decided. Keyed on the dispute id so release-on-REJECTED knows exactly which block to lift.
      if (roomMember != null) {
        payoutBlockService.blockForRoomMember(
            roomMember, PayoutBlockSourceType.DISPUTE, dispute.getId(), "DISPUTE");
      }
    }

    ticket.setAssignedAdmin(
        currentUser.getRole() == Role.ADMIN ? currentUser : ticket.getAssignedAdmin());
    ticket.setEscalatedToDispute(true);
    ticket.setStatus(SupportTicketStatus.ESCALATED);

    SupportMessage systemMessage =
        SupportMessage.builder()
            .ticket(ticket)
            .senderUser(currentUser)
            .senderRole(resolveStaffSenderRole(currentUser))
            .message("Заявка передана администратору как спор #" + dispute.getId())
            .build();

    supportMessageRepository.save(systemMessage);
    supportTicketRepository.save(ticket);

    // Let the user who filed the ticket know their case is now an admin dispute.
    notificationService.notify(
        ticket.getUser(),
        NotificationType.DISPUTE_OPENED,
        java.util.Map.of(),
        "/support",
        java.util.Map.of("ticketId", ticket.getId(), "disputeId", dispute.getId()));

    return publishAndReturn(ticket);
  }

  @Transactional
  public SupportTicket createSystemAccessIssueTicket(
      RoomMember roomMember, String subject, String message) {
    boolean alreadyExists =
        supportTicketRepository.existsByRoomMemberAndTopicAndStatusIn(
            roomMember,
            "ACCESS_ISSUE",
            List.of(
                SupportTicketStatus.OPEN,
                SupportTicketStatus.IN_PROGRESS,
                SupportTicketStatus.ESCALATED));

    if (alreadyExists) {
      return null;
    }

    SupportTicket ticket =
        SupportTicket.builder()
            .user(roomMember.getUser())
            .room(roomMember.getRoom())
            .roomMember(roomMember)
            .subject(subject)
            .topic("ACCESS_ISSUE")
            .status(SupportTicketStatus.OPEN)
            .priority(SupportTicketPriority.HIGH)
            .escalatedToDispute(false)
            .build();

    ticket = supportTicketRepository.save(ticket);

    payoutBlockService.blockForRoomMember(
        roomMember, PayoutBlockSourceType.SUPPORT_TICKET, ticket.getId(), "ACCESS_ISSUE");

    SupportMessage systemMessage =
        SupportMessage.builder()
            .ticket(ticket)
            .senderUser(roomMember.getUser())
            .senderRole(SupportSenderRole.SYSTEM)
            .message(message)
            .build();

    supportMessageRepository.save(systemMessage);

    if (!Boolean.TRUE.equals(roomMember.getRequiresAdminReview())) {
      roomMember.setRequiresAdminReview(true);
      roomMemberRepository.save(roomMember);
    }

    moderationService.enqueueMembershipForReview(roomMember, "PENDING_TIMEOUT", BigDecimal.ZERO);

    publishAndReturn(ticket);
    return ticket;
  }

  private void ensureStaff(User currentUser) {
    if (currentUser == null
        || (currentUser.getRole() != Role.ADMIN && currentUser.getRole() != Role.SUPPORT)) {
      throw new ForbiddenOperationException("Support or admin access required");
    }
  }

  private SupportSenderRole resolveStaffSenderRole(User currentUser) {
    return currentUser.getRole() == Role.ADMIN
        ? SupportSenderRole.ADMIN
        : SupportSenderRole.SUPPORT;
  }

  private SupportTicketResponse mapTicket(SupportTicket ticket) {
    List<SupportMessageDto> messages =
        supportMessageRepository.findByTicketOrderByCreatedAtAsc(ticket).stream()
            .map(
                msg ->
                    SupportMessageDto.builder()
                        .id(msg.getId())
                        .senderUserId(msg.getSenderUser().getId())
                        .senderRole(msg.getSenderRole().name())
                        .message(msg.getMessage())
                        .attachmentUrl(msg.getAttachmentUrl())
                        .createdAt(msg.getCreatedAt())
                        .build())
            .toList();

    Long disputeId = disputeRepository.findByTicket(ticket).map(Dispute::getId).orElse(null);

    return SupportTicketResponse.builder()
        .id(ticket.getId())
        .userId(ticket.getUser().getId())
        .roomId(ticket.getRoom() != null ? ticket.getRoom().getId() : null)
        .roomTitle(ticket.getRoom() != null ? ticket.getRoom().getTitle() : null)
        .roomMemberId(ticket.getRoomMember() != null ? ticket.getRoomMember().getId() : null)
        .subject(ticket.getSubject())
        .topic(ticket.getTopic())
        .status(ticket.getStatus().name())
        .priority(ticket.getPriority().name())
        .escalatedToDispute(ticket.getEscalatedToDispute())
        .disputeId(disputeId)
        .assignedAdminId(
            ticket.getAssignedAdmin() != null ? ticket.getAssignedAdmin().getId() : null)
        .assignedAdminDisplayName(
            ticket.getAssignedAdmin() != null ? ticket.getAssignedAdmin().getDisplayName() : null)
        .createdAt(ticket.getCreatedAt())
        .updatedAt(ticket.getUpdatedAt())
        .closedAt(ticket.getClosedAt())
        .messages(messages)
        .build();
  }
}
