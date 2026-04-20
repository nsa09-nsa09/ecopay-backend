package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.CreateSupportMessageRequest;
import kz.hrms.splitupauth.dto.CreateSupportTicketRequest;
import kz.hrms.splitupauth.dto.SupportMessageDto;
import kz.hrms.splitupauth.dto.SupportTicketResponse;
import kz.hrms.splitupauth.dto.UpdateSupportTicketStatusRequest;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.SupportMessageRepository;
import kz.hrms.splitupauth.repository.SupportTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import kz.hrms.splitupauth.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SupportTicketService {

    private final SupportTicketRepository supportTicketRepository;
    private final SupportMessageRepository supportMessageRepository;
    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final ModerationService moderationService;

    @Transactional
    public SupportTicketResponse createTicket(User currentUser, CreateSupportTicketRequest request) {
        Room room = null;
        RoomMember roomMember = null;

        if (request.getRoomId() != null) {
            room = roomRepository.findById(request.getRoomId())
                    .filter(r -> r.getDeletedAt() == null)
                    .orElseThrow(() -> new ResourceNotFoundException("Room not found"));
        }

        if (request.getRoomMemberId() != null) {
            roomMember = roomMemberRepository.findById(request.getRoomMemberId())
                    .filter(rm -> rm.getDeletedAt() == null)
                    .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));

            if (!roomMember.getUser().getId().equals(currentUser.getId())
                    && !roomMember.getRoom().getOwner().getId().equals(currentUser.getId())) {
                throw new ForbiddenOperationException("You cannot create ticket for this membership");
            }

            if (room == null) {
                room = roomMember.getRoom();
            }
        }

        SupportTicket ticket = SupportTicket.builder()
                .user(currentUser)
                .room(room)
                .roomMember(roomMember)
                .subject(request.getSubject())
                .topic(request.getTopic())
                .status(SupportTicketStatus.OPEN)
                .priority(roomMember != null ? SupportTicketPriority.HIGH : SupportTicketPriority.NORMAL)
                .escalatedToDispute(false)
                .build();

        ticket = supportTicketRepository.save(ticket);

        SupportMessage firstMessage = SupportMessage.builder()
                .ticket(ticket)
                .senderUser(currentUser)
                .senderRole(SupportSenderRole.USER)
                .message(request.getMessage())
                .build();

        supportMessageRepository.save(firstMessage);

        if (roomMember != null) {
            if (!Boolean.TRUE.equals(roomMember.getRequiresAdminReview())) {
                roomMember.setRequiresAdminReview(true);
                roomMemberRepository.save(roomMember);
            }

            moderationService.enqueueMembershipForReview(
                    roomMember,
                    "SUPPORT_TICKET",
                    BigDecimal.ZERO
            );
        }

        return mapTicket(ticket);
    }

    @Transactional(readOnly = true)
    public List<SupportTicketResponse> getMyTickets(User currentUser) {
        return supportTicketRepository.findByUserOrderByCreatedAtDesc(currentUser)
                .stream()
                .map(this::mapTicket)
                .toList();
    }

    @Transactional(readOnly = true)
    public SupportTicketResponse getMyTicket(Long ticketId, User currentUser) {
        SupportTicket ticket = supportTicketRepository.findByIdAndUser(ticketId, currentUser)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));

        return mapTicket(ticket);
    }

    @Transactional
    public SupportTicketResponse addMessage(Long ticketId, User currentUser, CreateSupportMessageRequest request) {
        SupportTicket ticket = supportTicketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));

        if (!ticket.getUser().getId().equals(currentUser.getId())) {
            throw new ForbiddenOperationException("You can only write to your own ticket");
        }

        if (ticket.getStatus() == SupportTicketStatus.CLOSED) {
            throw new InvalidRequestException("Cannot write to closed ticket");
        }

        SupportMessage message = SupportMessage.builder()
                .ticket(ticket)
                .senderUser(currentUser)
                .senderRole(SupportSenderRole.USER)
                .message(request.getMessage())
                .build();

        supportMessageRepository.save(message);

        if (ticket.getStatus() == SupportTicketStatus.WAITING_USER) {
            ticket.setStatus(SupportTicketStatus.IN_PROGRESS);
            supportTicketRepository.save(ticket);
        }

        return mapTicket(ticket);
    }

    @Transactional(readOnly = true)
    public List<SupportTicketResponse> getStaffQueue(User currentUser) {
        ensureStaff(currentUser);

        return supportTicketRepository.findByStatusInOrderByCreatedAtAsc(
                        List.of(
                                SupportTicketStatus.OPEN,
                                SupportTicketStatus.IN_PROGRESS,
                                SupportTicketStatus.WAITING_USER,
                                SupportTicketStatus.ESCALATED
                        )
                ).stream()
                .map(this::mapTicket)
                .toList();
    }
    //Get staff queue with pagination
    @Transactional(readOnly = true)
    public PageResponse<SupportTicketResponse> getStaffQueuePaged(User currentUser, int page, int size) {
        ensureStaff(currentUser);

        Pageable pageable = PageRequest.of(page, size);

        Page<SupportTicket> result = supportTicketRepository.findByStatusInOrderByCreatedAtAsc(
                List.of(
                        SupportTicketStatus.OPEN,
                        SupportTicketStatus.IN_PROGRESS,
                        SupportTicketStatus.WAITING_USER,
                        SupportTicketStatus.ESCALATED
                ),
                pageable
        );

        List<SupportTicketResponse> items = result.getContent()
                .stream()
                .map(this::mapTicket)
                .toList();

        return PageResponse.from(result, items);
    }
    @Transactional(readOnly = true)
    public List<SupportTicketResponse> getAssignedTickets(User currentUser) {
        ensureStaff(currentUser);

        return supportTicketRepository.findByAssignedAdminOrderByUpdatedAtDescCreatedAtDesc(currentUser)
                .stream()
                .map(this::mapTicket)
                .toList();
    }
    //Get assigned tickets with pagination
    @Transactional(readOnly = true)
    public PageResponse<SupportTicketResponse> getAssignedTicketsPaged(User currentUser, int page, int size) {
        ensureStaff(currentUser);

        Pageable pageable = PageRequest.of(page, size);

        Page<SupportTicket> result = supportTicketRepository.findByAssignedAdminOrderByUpdatedAtDescCreatedAtDesc(
                currentUser,
                pageable
        );

        List<SupportTicketResponse> items = result.getContent()
                .stream()
                .map(this::mapTicket)
                .toList();

        return PageResponse.from(result, items);
    }

    @Transactional(readOnly = true)
    public SupportTicketResponse getStaffTicket(Long ticketId, User currentUser) {
        ensureStaff(currentUser);

        SupportTicket ticket = supportTicketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));

        return mapTicket(ticket);
    }

    @Transactional
    public SupportTicketResponse assignTicketToMe(Long ticketId, User currentUser) {
        ensureStaff(currentUser);

        SupportTicket ticket = supportTicketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));

        if (ticket.getStatus() == SupportTicketStatus.CLOSED) {
            throw new InvalidRequestException("Cannot assign closed ticket");
        }

        ticket.setAssignedAdmin(currentUser);

        if (ticket.getStatus() == SupportTicketStatus.OPEN) {
            ticket.setStatus(SupportTicketStatus.IN_PROGRESS);
        }

        supportTicketRepository.save(ticket);
        return mapTicket(ticket);
    }

    @Transactional
    public SupportTicketResponse updateTicketStatus(
            Long ticketId,
            User currentUser,
            UpdateSupportTicketStatusRequest request
    ) {
        ensureStaff(currentUser);

        SupportTicket ticket = supportTicketRepository.findById(ticketId)
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
        return mapTicket(ticket);
    }

    @Transactional
    public SupportTicketResponse addStaffMessage(
            Long ticketId,
            User currentUser,
            CreateSupportMessageRequest request
    ) {
        ensureStaff(currentUser);

        SupportTicket ticket = supportTicketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));

        if (ticket.getStatus() == SupportTicketStatus.CLOSED) {
            throw new InvalidRequestException("Cannot write to closed ticket");
        }

        ticket.setAssignedAdmin(currentUser);

        SupportMessage message = SupportMessage.builder()
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
        return mapTicket(ticket);
    }

    @Transactional
    public SupportTicketResponse escalateTicketToAdmin(Long ticketId, User currentUser) {
        ensureStaff(currentUser);

        SupportTicket ticket = supportTicketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));

        if (ticket.getStatus() == SupportTicketStatus.CLOSED) {
            throw new InvalidRequestException("Cannot escalate closed ticket");
        }

        if (ticket.getRoomMember() == null) {
            throw new InvalidRequestException("Only membership-linked tickets can be escalated for now");
        }

        RoomMember roomMember = ticket.getRoomMember();

        if (!Boolean.TRUE.equals(roomMember.getRequiresAdminReview())) {
            roomMember.setRequiresAdminReview(true);
            roomMemberRepository.save(roomMember);
        }

        moderationService.enqueueMembershipForReview(
                roomMember,
                "SUPPORT_ESCALATION",
                BigDecimal.ZERO
        );

        ticket.setAssignedAdmin(currentUser.getRole() == Role.ADMIN ? currentUser : ticket.getAssignedAdmin());
        ticket.setEscalatedToDispute(true);
        ticket.setStatus(SupportTicketStatus.ESCALATED);

        SupportMessage systemMessage = SupportMessage.builder()
                .ticket(ticket)
                .senderUser(currentUser)
                .senderRole(resolveStaffSenderRole(currentUser))
                .message("Ticket escalated to admin moderation")
                .build();

        supportMessageRepository.save(systemMessage);
        supportTicketRepository.save(ticket);

        return mapTicket(ticket);
    }

    @Transactional
    public SupportTicket createSystemAccessIssueTicket(RoomMember roomMember, String subject, String message) {
        boolean alreadyExists = supportTicketRepository.existsByRoomMemberAndTopicAndStatusIn(
                roomMember,
                "ACCESS_ISSUE",
                List.of(
                        SupportTicketStatus.OPEN,
                        SupportTicketStatus.IN_PROGRESS,
                        SupportTicketStatus.ESCALATED
                )
        );

        if (alreadyExists) {
            return null;
        }

        SupportTicket ticket = SupportTicket.builder()
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

        SupportMessage systemMessage = SupportMessage.builder()
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

        moderationService.enqueueMembershipForReview(
                roomMember,
                "PENDING_TIMEOUT",
                BigDecimal.ZERO
        );

        return ticket;
    }

    private void ensureStaff(User currentUser) {
        if (currentUser == null || (currentUser.getRole() != Role.ADMIN && currentUser.getRole() != Role.SUPPORT)) {
            throw new ForbiddenOperationException("Support or admin access required");
        }
    }

    private SupportSenderRole resolveStaffSenderRole(User currentUser) {
        return currentUser.getRole() == Role.ADMIN
                ? SupportSenderRole.ADMIN
                : SupportSenderRole.SUPPORT;
    }

    private SupportTicketResponse mapTicket(SupportTicket ticket) {
        List<SupportMessageDto> messages = supportMessageRepository.findByTicketOrderByCreatedAtAsc(ticket)
                .stream()
                .map(msg -> SupportMessageDto.builder()
                        .id(msg.getId())
                        .senderUserId(msg.getSenderUser().getId())
                        .senderRole(msg.getSenderRole().name())
                        .message(msg.getMessage())
                        .attachmentUrl(msg.getAttachmentUrl())
                        .createdAt(msg.getCreatedAt())
                        .build())
                .toList();

        return SupportTicketResponse.builder()
                .id(ticket.getId())
                .userId(ticket.getUser().getId())
                .roomId(ticket.getRoom() != null ? ticket.getRoom().getId() : null)
                .roomMemberId(ticket.getRoomMember() != null ? ticket.getRoomMember().getId() : null)
                .subject(ticket.getSubject())
                .topic(ticket.getTopic())
                .status(ticket.getStatus().name())
                .priority(ticket.getPriority().name())
                .escalatedToDispute(ticket.getEscalatedToDispute())
                .createdAt(ticket.getCreatedAt())
                .updatedAt(ticket.getUpdatedAt())
                .closedAt(ticket.getClosedAt())
                .messages(messages)
                .build();
    }
}