package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kz.hrms.splitupauth.dto.DisputeDecisionRequest;
import kz.hrms.splitupauth.dto.DisputeResponse;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import kz.hrms.splitupauth.dto.ApplyDisputeSanctionsRequest;
import kz.hrms.splitupauth.dto.CreateRefundRequest;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.UserRepository;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import kz.hrms.splitupauth.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
@Service
@RequiredArgsConstructor
public class DisputeService {

    private final DisputeRepository disputeRepository;
    private final SupportTicketRepository supportTicketRepository;
    private final AdminActionLogRepository adminActionLogRepository;
    private final RoomEventLogRepository roomEventLogRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final RefundService refundService;

    @Transactional
    public DisputeResponse openFromTicket(Long ticketId, User currentUser) {
        ensureStaff(currentUser);

        SupportTicket ticket = supportTicketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));

        if (ticket.getRoom() == null) {
            throw new InvalidRequestException("Ticket must be linked to room to open dispute");
        }

        Dispute existing = disputeRepository.findByTicket(ticket).orElse(null);
        if (existing != null) {
            return map(existing);
        }

        Dispute dispute = Dispute.builder()
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

        return map(dispute);
    }

    @Transactional(readOnly = true)
    public List<DisputeResponse> getAdminQueue(User currentUser) {
        ensureAdmin(currentUser);

        return disputeRepository.findByStatusInOrderByCreatedAtAsc(
                        List.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW)
                ).stream()
                .map(this::map)
                .toList();
    }
    @Transactional(readOnly = true)
    public PageResponse<DisputeResponse> getAdminQueuePaged(User currentUser, int page, int size) {
        ensureAdmin(currentUser);

        Pageable pageable = PageRequest.of(page, size);

        Page<Dispute> result = disputeRepository.findByStatusInOrderByCreatedAtAsc(
                List.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW),
                pageable
        );

        List<DisputeResponse> items = result.getContent()
                .stream()
                .map(this::map)
                .toList();

        return PageResponse.from(result, items);
    }
    @Transactional(readOnly = true)
    public DisputeResponse getAdminDispute(Long disputeId, User currentUser) {
        ensureAdmin(currentUser);

        Dispute dispute = disputeRepository.findById(disputeId)
                .orElseThrow(() -> new ResourceNotFoundException("Dispute not found"));

        return map(dispute);
    }

    @Transactional(readOnly = true)
    public List<DisputeResponse> getMyDisputes(User currentUser) {
        return disputeRepository.findByOpenedByUserOrderByCreatedAtDesc(currentUser)
                .stream()
                .map(this::map)
                .toList();
    }

    @Transactional(readOnly = true)
    public DisputeResponse getMyDispute(Long disputeId, User currentUser) {
        Dispute dispute = disputeRepository.findByIdAndOpenedByUser(disputeId, currentUser)
                .orElseThrow(() -> new ResourceNotFoundException("Dispute not found"));

        return map(dispute);
    }

    @Transactional
    public DisputeResponse assignToMe(Long disputeId, User currentUser) {
        ensureAdmin(currentUser);

        Dispute dispute = disputeRepository.findById(disputeId)
                .orElseThrow(() -> new ResourceNotFoundException("Dispute not found"));

        if (dispute.getStatus() == DisputeStatus.RESOLVED || dispute.getStatus() == DisputeStatus.REJECTED) {
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
            HttpServletRequest httpRequest
    ) {
        ensureAdmin(currentUser);

        Dispute dispute = disputeRepository.findById(disputeId)
                .orElseThrow(() -> new ResourceNotFoundException("Dispute not found"));

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
                        .build()
        );

        if (dispute.getRoom() != null) {
            ObjectNode newState = JsonNodeFactory.instance.objectNode();
            newState.put("disputeStatus", dispute.getStatus().name());
            newState.put("decision", dispute.getDecision());

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
                            .build()
            );
        }

        return map(dispute);
    }

    private void ensureAdmin(User currentUser) {
        if (currentUser == null || currentUser.getRole() != Role.ADMIN) {
            throw new ForbiddenOperationException("Admin access required");
        }
    }

    private void ensureStaff(User currentUser) {
        if (currentUser == null || (currentUser.getRole() != Role.ADMIN && currentUser.getRole() != Role.SUPPORT)) {
            throw new ForbiddenOperationException("Support or admin access required");
        }
    }
    @Transactional
    public DisputeResponse applyOwnerViolationSanctions(
            Long disputeId,
            User currentUser,
            ApplyDisputeSanctionsRequest request,
            jakarta.servlet.http.HttpServletRequest httpRequest
    ) {
        ensureAdmin(currentUser);

        Dispute dispute = disputeRepository.findById(disputeId)
                .orElseThrow(() -> new ResourceNotFoundException("Dispute not found"));

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
                        .build()
        );

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
                        .build()
        );

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
                        .build()
        );

        if (Boolean.TRUE.equals(request.getCreateRefund())) {
            if (request.getPaymentTransactionId() == null || request.getRefundAmount() == null) {
                throw new InvalidRequestException("Payment transaction and refund amount are required when createRefund=true");
            }

            CreateRefundRequest refundRequest = new CreateRefundRequest();
            refundRequest.setPaymentTransactionId(request.getPaymentTransactionId());
            refundRequest.setDisputeId(dispute.getId());
            refundRequest.setAmount(request.getRefundAmount());
            refundRequest.setReason(request.getReason());
            refundRequest.setIdempotencyKey("dispute-" + dispute.getId() + "-refund-" + request.getPaymentTransactionId());

            refundService.createRefund(currentUser, refundRequest, httpRequest);
        }

        dispute.setStatus(DisputeStatus.RESOLVED);
        dispute.setDecision("OWNER_VIOLATION_CONFIRMED");
        dispute.setDecisionComment(request.getReason());
        dispute.setResolvedAt(java.time.LocalDateTime.now());
        dispute.setAssignedAdmin(currentUser);
        disputeRepository.save(dispute);

        return map(dispute);
    }
    private DisputeResponse map(Dispute dispute) {
        return DisputeResponse.builder()
                .id(dispute.getId())
                .roomId(dispute.getRoom() != null ? dispute.getRoom().getId() : null)
                .roomMemberId(dispute.getRoomMember() != null ? dispute.getRoomMember().getId() : null)
                .ticketId(dispute.getTicket() != null ? dispute.getTicket().getId() : null)
                .openedByUserId(dispute.getOpenedByUser().getId())
                .assignedAdminId(dispute.getAssignedAdmin() != null ? dispute.getAssignedAdmin().getId() : null)
                .reasonCode(dispute.getReasonCode())
                .description(dispute.getDescription())
                .status(dispute.getStatus().name())
                .decision(dispute.getDecision())
                .decisionComment(dispute.getDecisionComment())
                .resolvedAt(dispute.getResolvedAt())
                .createdAt(dispute.getCreatedAt())
                .updatedAt(dispute.getUpdatedAt())
                .build();
    }
}