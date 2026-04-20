package kz.hrms.splitupauth.service;

import jakarta.servlet.http.HttpServletRequest;
import kz.hrms.splitupauth.dto.AdminDecisionRequest;
import kz.hrms.splitupauth.dto.BatchConfirmRequest;
import kz.hrms.splitupauth.dto.ModerationQueueItemDto;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import kz.hrms.splitupauth.entity.Role;
@Service
@RequiredArgsConstructor
public class ModerationService {

    private final ModerationQueueRepository moderationQueueRepository;
    private final AdminActionLogRepository adminActionLogRepository;
    private final RoomEventLogRepository roomEventLogRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final SupportTicketRepository supportTicketRepository;
    private final DisputeRepository disputeRepository;

    @Transactional(readOnly = true)
    public List<ModerationQueueItemDto> getOpenQueue(User currentUser) {
        ensureAdmin(currentUser);

        return moderationQueueRepository.findByStatusOrderByCreatedAtAsc(ModerationQueueStatus.OPEN)
                .stream()
                .map(this::mapQueueItem)
                .toList();
    }

    @Transactional
    public void enqueueMembershipForReview(RoomMember roomMember, String reasonCode, BigDecimal riskScore) {
        boolean exists = moderationQueueRepository.existsByRoomMemberAndStatusIn(
                roomMember,
                List.of(ModerationQueueStatus.OPEN, ModerationQueueStatus.IN_REVIEW)
        );

        if (exists) {
            return;
        }

        ModerationQueue item = ModerationQueue.builder()
                .entityType("ROOM_MEMBER")
                .entityId(roomMember.getId())
                .room(roomMember.getRoom())
                .roomMember(roomMember)
                .reasonCode(reasonCode)
                .riskScore(riskScore != null ? riskScore : BigDecimal.ZERO)
                .status(ModerationQueueStatus.OPEN)
                .build();

        moderationQueueRepository.save(item);
    }

    @Transactional
    public ModerationQueueItemDto assignToMe(Long queueId, User currentUser) {
        ensureAdmin(currentUser);

        ModerationQueue item = moderationQueueRepository.findById(queueId)
                .orElseThrow(() -> new ResourceNotFoundException("Moderation queue item not found"));

        if (item.getStatus() != ModerationQueueStatus.OPEN && item.getStatus() != ModerationQueueStatus.IN_REVIEW) {
            throw new InvalidRequestException("Only OPEN or IN_REVIEW item can be assigned");
        }

        item.setAssignedAdmin(currentUser);
        item.setStatus(ModerationQueueStatus.IN_REVIEW);
        moderationQueueRepository.save(item);

        return mapQueueItem(item);
    }

    @Transactional
    public ModerationQueueItemDto confirmMembership(
            Long queueId,
            User currentUser,
            AdminDecisionRequest request,
            HttpServletRequest httpRequest
    ) {
        ensureAdmin(currentUser);

        ModerationQueue item = moderationQueueRepository.findById(queueId)
                .orElseThrow(() -> new ResourceNotFoundException("Moderation queue item not found"));

        if (item.getRoomMember() == null) {
            throw new InvalidRequestException("Queue item is not linked to membership");
        }

        RoomMember roomMember = item.getRoomMember();

        if (roomMember.getStatus() != MemberStatus.PENDING) {
            throw new InvalidRequestException("Only PENDING membership can be confirmed by admin");
        }

        roomMember.setRequiresAdminReview(false);
        roomMember.setStatus(MemberStatus.ACTIVE);

        if (roomMember.getActivatedAt() == null) {
            roomMember.setActivatedAt(LocalDateTime.now());
        }

        roomMemberRepository.save(roomMember);

        item.setAssignedAdmin(currentUser);
        item.setStatus(ModerationQueueStatus.RESOLVED);
        moderationQueueRepository.save(item);

        adminActionLogRepository.save(
                AdminActionLog.builder()
                        .eventId(UUID.randomUUID())
                        .adminUser(currentUser)
                        .actionType(AdminActionType.ACCESS_CONFIRMED)
                        .entityType("ROOM_MEMBER")
                        .entityId(roomMember.getId())
                        .reason(request.getReason())
                        .ipAddress(httpRequest.getRemoteAddr())
                        .userAgent(httpRequest.getHeader("User-Agent"))
                        .build()
        );

        roomEventLogRepository.save(
                RoomEventLog.builder()
                        .eventId(UUID.randomUUID())
                        .actorUser(currentUser)
                        .actorRole("ADMIN")
                        .room(roomMember.getRoom())
                        .roomMember(roomMember)
                        .eventType("ADMIN_CONFIRMED_ACCESS")
                        .newState(statusState("ACTIVE"))
                        .ipAddress(httpRequest.getRemoteAddr())
                        .userAgent(httpRequest.getHeader("User-Agent"))
                        .build()
        );

        return mapQueueItem(item);
    }

    @Transactional
    public ModerationQueueItemDto rejectMembership(
            Long queueId,
            User currentUser,
            AdminDecisionRequest request,
            HttpServletRequest httpRequest
    ) {
        ensureAdmin(currentUser);

        ModerationQueue item = moderationQueueRepository.findById(queueId)
                .orElseThrow(() -> new ResourceNotFoundException("Moderation queue item not found"));

        if (item.getRoomMember() == null) {
            throw new InvalidRequestException("Queue item is not linked to membership");
        }

        RoomMember roomMember = item.getRoomMember();

        if (roomMember.getStatus() != MemberStatus.PENDING) {
            throw new InvalidRequestException("Only PENDING membership can be rejected by admin");
        }

        roomMember.setStatus(MemberStatus.REJECTED);
        roomMember.setRejectedAt(LocalDateTime.now());
        roomMember.setRequiresAdminReview(false);
        roomMemberRepository.save(roomMember);

        item.setAssignedAdmin(currentUser);
        item.setStatus(ModerationQueueStatus.REJECTED);
        moderationQueueRepository.save(item);

        adminActionLogRepository.save(
                AdminActionLog.builder()
                        .eventId(UUID.randomUUID())
                        .adminUser(currentUser)
                        .actionType(AdminActionType.ACCESS_REJECTED)
                        .entityType("ROOM_MEMBER")
                        .entityId(roomMember.getId())
                        .reason(request.getReason())
                        .ipAddress(httpRequest.getRemoteAddr())
                        .userAgent(httpRequest.getHeader("User-Agent"))
                        .build()
        );

        roomEventLogRepository.save(
                RoomEventLog.builder()
                        .eventId(UUID.randomUUID())
                        .actorUser(currentUser)
                        .actorRole("ADMIN")
                        .room(roomMember.getRoom())
                        .roomMember(roomMember)
                        .eventType("ADMIN_REJECTED_ACCESS")
                        .newState(statusState("REJECTED"))
                        .ipAddress(httpRequest.getRemoteAddr())
                        .userAgent(httpRequest.getHeader("User-Agent"))
                        .build()
        );

        return mapQueueItem(item);
    }

    @Transactional
    public void blockRoom(
            Long roomId,
            User currentUser,
            AdminDecisionRequest request,
            HttpServletRequest httpRequest
    ) {
        ensureAdmin(currentUser);

        Room room = roomRepository.findById(roomId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

        room.setStatus(RoomStatus.BLOCKED);
        room.setBlockedAt(LocalDateTime.now());
        room.setBlockReason(request.getReason());
        roomRepository.save(room);

        adminActionLogRepository.save(
                AdminActionLog.builder()
                        .eventId(UUID.randomUUID())
                        .adminUser(currentUser)
                        .actionType(AdminActionType.ROOM_BLOCKED)
                        .entityType("ROOM")
                        .entityId(room.getId())
                        .reason(request.getReason())
                        .ipAddress(httpRequest.getRemoteAddr())
                        .userAgent(httpRequest.getHeader("User-Agent"))
                        .build()
        );

        roomEventLogRepository.save(
                RoomEventLog.builder()
                        .eventId(UUID.randomUUID())
                        .actorUser(currentUser)
                        .actorRole("ADMIN")
                        .room(room)
                        .eventType("ROOM_BLOCKED")
                        .newState(statusState("BLOCKED"))
                        .ipAddress(httpRequest.getRemoteAddr())
                        .userAgent(httpRequest.getHeader("User-Agent"))
                        .build()
        );
    }

    @Transactional
    public void banUser(
            Long userId,
            User currentUser,
            AdminDecisionRequest request,
            HttpServletRequest httpRequest
    ) {
        ensureAdmin(currentUser);

        User target = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        target.setStatus(UserStatus.BANNED);
        userRepository.save(target);

        adminActionLogRepository.save(
                AdminActionLog.builder()
                        .eventId(UUID.randomUUID())
                        .adminUser(currentUser)
                        .actionType(AdminActionType.USER_BANNED)
                        .entityType("USER")
                        .entityId(target.getId())
                        .reason(request.getReason())
                        .ipAddress(httpRequest.getRemoteAddr())
                        .userAgent(httpRequest.getHeader("User-Agent"))
                        .build()
        );
    }

    private ModerationQueueItemDto mapQueueItem(ModerationQueue item) {
        return ModerationQueueItemDto.builder()
                .id(item.getId())
                .entityType(item.getEntityType())
                .entityId(item.getEntityId())
                .roomId(item.getRoom() != null ? item.getRoom().getId() : null)
                .roomMemberId(item.getRoomMember() != null ? item.getRoomMember().getId() : null)
                .reasonCode(item.getReasonCode())
                .riskScore(item.getRiskScore())
                .status(item.getStatus().name())
                .assignedAdminId(item.getAssignedAdmin() != null ? item.getAssignedAdmin().getId() : null)
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .build();
    }
    @Transactional
    public List<ModerationQueueItemDto> batchConfirmMemberships(
            User currentUser,
            BatchConfirmRequest request,
            HttpServletRequest httpRequest
    ) {
        ensureAdmin(currentUser);

        List<ModerationQueue> items = moderationQueueRepository.findAllById(request.getQueueIds());

        if (items.size() != request.getQueueIds().size()) {
            throw new ResourceNotFoundException("One or more moderation queue items not found");
        }

        Long roomId = null;

        for (ModerationQueue item : items) {
            if (item.getRoom() == null || item.getRoomMember() == null) {
                throw new InvalidRequestException("Batch confirm supports only room-linked membership queue items");
            }

            if (roomId == null) {
                roomId = item.getRoom().getId();
            } else if (!roomId.equals(item.getRoom().getId())) {
                throw new InvalidRequestException("Batch confirm is allowed only within one room");
            }

            if (item.getStatus() != ModerationQueueStatus.OPEN && item.getStatus() != ModerationQueueStatus.IN_REVIEW) {
                throw new InvalidRequestException("Only OPEN or IN_REVIEW items can be batch confirmed");
            }

            if (!"ADMIN_REQUIRED".equals(item.getReasonCode())) {
                throw new InvalidRequestException("Batch confirm is allowed only for ADMIN_REQUIRED items without red flags");
            }
        }

        for (ModerationQueue item : items) {
            RoomMember roomMember = item.getRoomMember();

            if (roomMember.getStatus() != MemberStatus.PENDING) {
                throw new InvalidRequestException("Only PENDING memberships can be batch confirmed");
            }

            if (hasOpenSupportTicket(roomMember) || hasOpenDispute(roomMember)) {
                throw new InvalidRequestException("Batch confirm is not allowed for memberships with open support/dispute");
            }

            roomMember.setRequiresAdminReview(false);
            roomMember.setStatus(MemberStatus.ACTIVE);

            if (roomMember.getActivatedAt() == null) {
                roomMember.setActivatedAt(LocalDateTime.now());
            }

            roomMemberRepository.save(roomMember);

            item.setAssignedAdmin(currentUser);
            item.setStatus(ModerationQueueStatus.RESOLVED);
            moderationQueueRepository.save(item);

            adminActionLogRepository.save(
                    AdminActionLog.builder()
                            .eventId(UUID.randomUUID())
                            .adminUser(currentUser)
                            .actionType(AdminActionType.BATCH_CONFIRM)
                            .entityType("ROOM_MEMBER")
                            .entityId(roomMember.getId())
                            .reason(request.getReason())
                            .ipAddress(httpRequest.getRemoteAddr())
                            .userAgent(httpRequest.getHeader("User-Agent"))
                            .build()
            );

            roomEventLogRepository.save(
                    RoomEventLog.builder()
                            .eventId(UUID.randomUUID())
                            .actorUser(currentUser)
                            .actorRole("ADMIN")
                            .room(roomMember.getRoom())
                            .roomMember(roomMember)
                            .eventType("ADMIN_BATCH_CONFIRMED_ACCESS")
                            .newState(statusState("ACTIVE"))
                            .ipAddress(httpRequest.getRemoteAddr())
                            .userAgent(httpRequest.getHeader("User-Agent"))
                            .build()
            );
        }

        return items.stream().map(this::mapQueueItem).toList();
    }
    private ObjectNode statusState(String status) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("status", status);
        return node;
    }

    private void ensureAdmin(User currentUser) {
        if (currentUser == null || currentUser.getRole() != Role.ADMIN) {
            throw new ForbiddenOperationException("Admin access required");
        }
    }
    private boolean hasOpenSupportTicket(RoomMember roomMember) {
        return supportTicketRepository.existsByRoomMemberAndStatusIn(
                roomMember,
                List.of(
                        SupportTicketStatus.OPEN,
                        SupportTicketStatus.IN_PROGRESS,
                        SupportTicketStatus.WAITING_USER,
                        SupportTicketStatus.ESCALATED
                )
        );
    }

    private boolean hasOpenDispute(RoomMember roomMember) {
        return disputeRepository.existsByRoomMemberAndStatusIn(
                roomMember,
                List.of(
                        DisputeStatus.OPEN,
                        DisputeStatus.UNDER_REVIEW
                )
        );
    }
}