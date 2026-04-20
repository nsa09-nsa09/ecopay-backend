package kz.hrms.splitupauth.service;

import jakarta.servlet.http.HttpServletRequest;
import kz.hrms.splitupauth.dto.*;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.*;
import kz.hrms.splitupauth.security.FieldEncryptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kz.hrms.splitupauth.entity.Role;


@Service
@RequiredArgsConstructor
public class RoomMemberService {

    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final RoomMemberIdentifierRepository roomMemberIdentifierRepository;
    private final RoomMemberMapper roomMemberMapper;
    private final FieldEncryptionService fieldEncryptionService;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final RoomEventLogRepository roomEventLogRepository;
    private final SupportTicketRepository supportTicketRepository;
    private final ModerationService moderationService;
    private final DisputeRepository disputeRepository;
    private final ModerationQueueRepository moderationQueueRepository;
    @Transactional
    public RoomMemberDto joinRoom(Long roomId, User currentUser, JoinRoomRequest request) {
        Room room = roomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));


        validateJoin(room, currentUser, request);

        RoomMember roomMember = RoomMember.builder()
                .room(room)
                .user(currentUser)
                .status(MemberStatus.APPLIED)
                .requiresAdminReview(false)
                .consentAcceptedAt(LocalDateTime.now())
                .build();

        roomMember = roomMemberRepository.save(roomMember);

        if (room.getRoomType() == RoomType.TELECOM) {
            String rawIdentifier = request.getIdentifierValue();

            RoomMemberIdentifier identifier = RoomMemberIdentifier.builder()
                    .roomMember(roomMember)
                    .identifierType(request.getIdentifierType())
                    .identifierEncrypted(fieldEncryptionService.encrypt(rawIdentifier))
                    .identifierMasked(maskIdentifier(rawIdentifier))
                    .isValidFormat(isValidIdentifierFormat(rawIdentifier))
                    .build();

            roomMemberIdentifierRepository.save(identifier);
        }

        return roomMemberMapper.toDto(roomMember);
    }

    @Transactional(readOnly = true)
    public PagedResponse<RoomMemberDto> getRoomMembers(Long roomId, int page, int size, User currentUser) {
        Room room = roomRepository.findById(roomId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

        if (!room.getOwner().getId().equals(currentUser.getId())) {
            throw new ForbiddenOperationException("Only room owner can view room members");
        }

        if (page < 0) {
            page = 0;
        }

        if (size <= 0) {
            size = 20;
        }

        if (size > 100) {
            size = 100;
        }

        Pageable pageable = PageRequest.of(page, size);
        Page<RoomMember> resultPage = roomMemberRepository
                .findByRoomAndDeletedAtIsNullOrderByCreatedAtAsc(room, pageable);

        return PagedResponse.<RoomMemberDto>builder()
                .items(resultPage.getContent().stream().map(roomMemberMapper::toDto).toList())
                .page(resultPage.getNumber())
                .size(resultPage.getSize())
                .totalItems(resultPage.getTotalElements())
                .totalPages(resultPage.getTotalPages())
                .hasNext(resultPage.hasNext())
                .hasPrevious(resultPage.hasPrevious())
                .build();
    }

    @Transactional(readOnly = true)
    public MyRoomMembershipDto getMyMembership(Long roomId, User currentUser) {
        Room room = roomRepository.findById(roomId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

        RoomMember roomMember = roomMemberRepository.findByRoomAndUserAndDeletedAtIsNull(room, currentUser)
                .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));

        RoomMemberIdentifier identifier = roomMemberIdentifierRepository.findByRoomMember(roomMember)
                .orElse(null);

        return roomMemberMapper.toMyDto(roomMember, identifier);
    }

    @Transactional
    public RoomMemberDto confirmOwnerAccess(
            Long roomId,
            Long memberId,
            User currentUser,
            ConfirmOwnerAccessRequest request
    ) {
        Room room = roomRepository.findById(roomId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

        if (!room.getOwner().getId().equals(currentUser.getId())) {
            throw new ForbiddenOperationException("Only room owner can confirm access");
        }

        RoomMember roomMember = roomMemberRepository.findByIdAndRoomAndDeletedAtIsNull(memberId, room)
                .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));

        if (roomMember.getStatus() != MemberStatus.PENDING) {
            throw new InvalidRequestException("Access can only be confirmed for PENDING membership");
        }

        if (roomMember.getOwnerAccessConfirmedAt() == null) {
            roomMember.setOwnerAccessConfirmedAt(LocalDateTime.now());
        }

        roomMember.setAccessMethod(request.getAccessMethod());

        tryActivateMembership(roomMember);

        roomMemberRepository.save(roomMember);

        return roomMemberMapper.toDto(roomMember);
    }

    @Transactional
    public MyRoomMembershipDto confirmMemberAccess(Long roomId, User currentUser) {
        Room room = roomRepository.findById(roomId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

        RoomMember roomMember = roomMemberRepository.findByRoomAndUserAndDeletedAtIsNull(room, currentUser)
                .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));

        if (roomMember.getStatus() != MemberStatus.PENDING) {
            throw new InvalidRequestException("Only PENDING membership can be confirmed");
        }

        if (roomMember.getOwnerAccessConfirmedAt() == null) {
            throw new InvalidRequestException("Owner has not confirmed access yet");
        }

        if (roomMember.getMemberConfirmedAt() == null) {
            roomMember.setMemberConfirmedAt(LocalDateTime.now());
        }

        tryActivateMembership(roomMember);

        roomMemberRepository.save(roomMember);

        RoomMemberIdentifier identifier = roomMemberIdentifierRepository.findByRoomMember(roomMember)
                .orElse(null);

        return roomMemberMapper.toMyDto(roomMember, identifier);
    }
    @Transactional
    public void markMembershipAsPaid(RoomMember roomMember) {
        if (roomMember.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Membership not found");
        }

        if (roomMember.getStatus() == MemberStatus.PENDING || roomMember.getStatus() == MemberStatus.ACTIVE) {
            return;
        }

        if (roomMember.getStatus() != MemberStatus.APPLIED) {
            throw new InvalidRequestException("Only APPLIED membership can be marked as paid");
        }

        roomMember.setStatus(MemberStatus.PENDING);

        boolean requiresAdminReview = shouldRequireAdminReviewAfterPayment(roomMember);
        roomMember.setRequiresAdminReview(requiresAdminReview);

        roomMemberRepository.save(roomMember);

        if (requiresAdminReview) {
            moderationService.enqueueMembershipForReview(
                    roomMember,
                    resolveModerationReasonCode(roomMember),
                    java.math.BigDecimal.ZERO
            );
        } else {
            tryActivateMembership(roomMember);
            roomMemberRepository.save(roomMember);
        }
    }
    private void tryActivateMembership(RoomMember roomMember) {
        if (roomMember.getStatus() != MemberStatus.PENDING) {
            return;
        }

        if (Boolean.TRUE.equals(roomMember.getRequiresAdminReview())) {
            return;
        }

        if (roomMember.getRoom().getVerificationMode() == VerificationMode.ADMIN_REQUIRED) {
            return;
        }

        if (roomMember.getOwnerAccessConfirmedAt() == null) {
            return;
        }

        if (roomMember.getMemberConfirmedAt() == null) {
            return;
        }

        if (roomMember.getUser().getStatus() == UserStatus.BANNED) {
            return;
        }

        if (roomMember.getRoom().getOwner().getStatus() == UserStatus.BANNED) {
            return;
        }

        if (hasOpenAutoActivationBlocker(roomMember)) {
            return;
        }

        if (roomMember.getRoom().getRoomType() == RoomType.TELECOM) {
            RoomMemberIdentifier identifier = roomMemberIdentifierRepository.findByRoomMember(roomMember)
                    .orElse(null);

            if (identifier == null || !Boolean.TRUE.equals(identifier.getIsValidFormat())) {
                return;
            }
        }

        roomMember.setStatus(MemberStatus.ACTIVE);

        if (roomMember.getActivatedAt() == null) {
            roomMember.setActivatedAt(LocalDateTime.now());
        }
    }
    private void validateJoin(Room room, User currentUser, JoinRoomRequest request) {
        if (room.getOwner().getId().equals(currentUser.getId())) {
            throw new InvalidRequestException("Room owner cannot join own room");
        }

        if (!(room.getStatus() == RoomStatus.OPEN)) {
            throw new InvalidRequestException("Room is not available for joining");
        }

        if (!room.getStartDate().isAfter(LocalDateTime.now())) {
            throw new InvalidRequestException("Cannot join room after start date");
        }

        boolean consentAccepted = Boolean.TRUE.equals(request.getConsentAccepted());
        if (!consentAccepted) {
            throw new InvalidRequestException("Consent must be accepted");
        }

        roomMemberRepository.findByRoomAndUserAndDeletedAtIsNull(room, currentUser)
                .ifPresent(existing -> {
                    throw new InvalidRequestException("User has already joined this room");
                });

        long occupiedSlots = roomMemberRepository.countByRoomAndStatusInAndDeletedAtIsNull(
                room,
                List.of(MemberStatus.PENDING, MemberStatus.ACTIVE)
        );

        if (occupiedSlots >= room.getMaxMembers() - 1) {
            throw new InvalidRequestException("No available slots in this room");
        }

        if (room.getRoomType() == RoomType.TELECOM) {
            if (request.getIdentifierType() == null) {
                throw new InvalidRequestException("Identifier type is required for TELECOM room");
            }

            if (request.getIdentifierValue() == null || request.getIdentifierValue().isBlank()) {
                throw new InvalidRequestException("Identifier value is required for TELECOM room");
            }
        }
    }

    private String maskIdentifier(String value) {
        if (value == null || value.length() < 4) {
            return "****";
        }

        if (value.length() <= 6) {
            return value.charAt(0) + "***" + value.charAt(value.length() - 1);
        }

        String start = value.substring(0, Math.min(4, value.length()));
        String end = value.substring(value.length() - 2);
        return start + "*****" + end;
    }
    @Transactional
    public RevealedIdentifierDto revealIdentifierForOwner(
            Long roomId,
            Long memberId,
            User currentUser,
            RevealIdentifierRequest request,
            HttpServletRequest httpRequest
    ) {
        Room room = roomRepository.findById(roomId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

        if (!room.getOwner().getId().equals(currentUser.getId())) {
            throw new ForbiddenOperationException("Only room owner can reveal member identifier");
        }

        if (room.getRoomType() != RoomType.TELECOM) {
            throw new InvalidRequestException("Identifier reveal is only available for TELECOM rooms");
        }

        RoomMember roomMember = roomMemberRepository.findByIdAndRoomAndDeletedAtIsNull(memberId, room)
                .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));

        boolean hasSuccessfulPayment = paymentTransactionRepository
                .existsByRoomMember_IdAndStatus(roomMember.getId(), PaymentTransactionStatus.SUCCESS);

        if (!hasSuccessfulPayment) {
            throw new ForbiddenOperationException("Identifier can only be revealed after successful payment");
        }

        RoomMemberIdentifier identifier = roomMemberIdentifierRepository.findByRoomMember(roomMember)
                .orElseThrow(() -> new ResourceNotFoundException("Identifier not found"));

        String decryptedIdentifier = fieldEncryptionService.decrypt(identifier.getIdentifierEncrypted());
        saveIdentifierRevealAudit(
                room,
                roomMember,
                currentUser,
                "OWNER",
                "IDENTIFIER_REVEALED_OWNER",
                request.getReason(),
                identifier,
                null,
                null,
                httpRequest
        );

        return RevealedIdentifierDto.builder()
                .roomId(room.getId())
                .roomMemberId(roomMember.getId())
                .identifierType(identifier.getIdentifierType().name())
                .identifierValue(decryptedIdentifier)
                .revealedForReason(request.getReason())
                .build();
    }
    @Transactional
    public RevealedIdentifierDto revealIdentifierForStaff(
            Long roomId,
            Long memberId,
            User currentUser,
            RevealIdentifierRequest request,
            HttpServletRequest httpRequest
    ) {
        ensureStaffCanReveal(currentUser);

        Room room = roomRepository.findById(roomId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

        if (room.getRoomType() != RoomType.TELECOM) {
            throw new InvalidRequestException("Identifier reveal is only available for TELECOM rooms");
        }

        RoomMember roomMember = roomMemberRepository.findByIdAndRoomAndDeletedAtIsNull(memberId, room)
                .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));

        ensureSuccessfulPayment(roomMember);

        RevealContextInfo revealContext = resolveRevealContext(roomMember, currentUser, request);

        RoomMemberIdentifier identifier = roomMemberIdentifierRepository.findByRoomMember(roomMember)
                .orElseThrow(() -> new ResourceNotFoundException("Identifier not found"));

        String decryptedIdentifier = fieldEncryptionService.decrypt(identifier.getIdentifierEncrypted());
        saveIdentifierRevealAudit(
                room,
                roomMember,
                currentUser,
                currentUser.getRole().name(),
                "IDENTIFIER_REVEALED_STAFF",
                request.getReason(),
                identifier,
                revealContext.type().name(),
                revealContext.id(),
                httpRequest
        );

        return RevealedIdentifierDto.builder()
                .roomId(room.getId())
                .roomMemberId(roomMember.getId())
                .identifierType(identifier.getIdentifierType().name())
                .identifierValue(decryptedIdentifier)
                .revealedForReason(request.getReason())
                .build();
    }
    private boolean shouldRequireAdminReviewAfterPayment(RoomMember roomMember) {
        Room room = roomMember.getRoom();

        if (room.getVerificationMode() == VerificationMode.ADMIN_REQUIRED) {
            return true;
        }

        if (room.getVerificationMode() == VerificationMode.RISK_BASED) {
            if (room.getRoomType() == RoomType.TELECOM) {
                RoomMemberIdentifier identifier = roomMemberIdentifierRepository.findByRoomMember(roomMember)
                        .orElse(null);

                if (identifier == null || !Boolean.TRUE.equals(identifier.getIsValidFormat())) {
                    return true;
                }
            }

            if (hasOpenAutoActivationBlocker(roomMember)) {
                return true;
            }
        }

        return false;
    }

    private String resolveModerationReasonCode(RoomMember roomMember) {
        if (roomMember.getRoom().getVerificationMode() == VerificationMode.ADMIN_REQUIRED) {
            return "ADMIN_REQUIRED";
        }

        if (roomMember.getRoom().getRoomType() == RoomType.TELECOM) {
            RoomMemberIdentifier identifier = roomMemberIdentifierRepository.findByRoomMember(roomMember)
                    .orElse(null);

            if (identifier == null || !Boolean.TRUE.equals(identifier.getIsValidFormat())) {
                return "INVALID_IDENTIFIER";
            }
        }

        if (hasOpenDispute(roomMember)) {
            return "OPEN_DISPUTE";
        }

        if (hasOpenSupportTicket(roomMember)) {
            return "SUPPORT_TICKET";
        }

        return "RISK_REVIEW";
    }

    private void ensureSuccessfulPayment(RoomMember roomMember) {
        boolean hasSuccessfulPayment = paymentTransactionRepository
                .existsByRoomMember_IdAndStatus(roomMember.getId(), PaymentTransactionStatus.SUCCESS);

        if (!hasSuccessfulPayment) {
            throw new ForbiddenOperationException("Identifier can only be revealed after successful payment");
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

    private boolean hasOpenAutoActivationBlocker(RoomMember roomMember) {
        return hasOpenSupportTicket(roomMember) || hasOpenDispute(roomMember);
    }

    private void ensureStaffCanReveal(User currentUser) {
        if (currentUser == null || (currentUser.getRole() != Role.ADMIN && currentUser.getRole() != Role.SUPPORT)) {
            throw new ForbiddenOperationException("Support or admin access required");
        }
    }

    private RevealContextInfo resolveRevealContext(
            RoomMember roomMember,
            User currentUser,
            RevealIdentifierRequest request
    ) {
        if (request.getContextType() == null || request.getContextType().isBlank() || request.getContextId() == null) {
            throw new ForbiddenOperationException(
                    "Admin/Support can reveal identifier only with moderation, support, or dispute context"
            );
        }

        IdentifierRevealContextType contextType;
        try {
            contextType = IdentifierRevealContextType.valueOf(request.getContextType().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new InvalidRequestException("Unsupported identifier reveal context");
        }

        Long contextId = request.getContextId();

        return switch (contextType) {
            case MODERATION -> resolveModerationContext(roomMember, currentUser, contextId);
            case SUPPORT -> resolveSupportContext(roomMember, currentUser, contextId);
            case DISPUTE -> resolveDisputeContext(roomMember, currentUser, contextId);
        };
    }

    private RevealContextInfo resolveModerationContext(RoomMember roomMember, User currentUser, Long contextId) {
        if (currentUser.getRole() != Role.ADMIN) {
            throw new ForbiddenOperationException("Only admin can reveal identifier in moderation context");
        }

        ModerationQueue item = moderationQueueRepository.findByIdAndRoomMemberAndStatusIn(
                        contextId,
                        roomMember,
                        List.of(ModerationQueueStatus.OPEN, ModerationQueueStatus.IN_REVIEW)
                )
                .orElseThrow(() -> new ForbiddenOperationException("Moderation context is not active for this membership"));

        ensureContextAssignment(item.getAssignedAdmin(), currentUser, "Moderation context");
        return new RevealContextInfo(IdentifierRevealContextType.MODERATION, item.getId());
    }

    private RevealContextInfo resolveSupportContext(RoomMember roomMember, User currentUser, Long contextId) {
        SupportTicket ticket = supportTicketRepository.findByIdAndRoomMemberAndStatusIn(
                        contextId,
                        roomMember,
                        List.of(
                                SupportTicketStatus.OPEN,
                                SupportTicketStatus.IN_PROGRESS,
                                SupportTicketStatus.WAITING_USER,
                                SupportTicketStatus.ESCALATED
                        )
                )
                .orElseThrow(() -> new ForbiddenOperationException("Support context is not active for this membership"));

        ensureContextAssignment(ticket.getAssignedAdmin(), currentUser, "Support context");
        return new RevealContextInfo(IdentifierRevealContextType.SUPPORT, ticket.getId());
    }

    private RevealContextInfo resolveDisputeContext(RoomMember roomMember, User currentUser, Long contextId) {
        Dispute dispute = disputeRepository.findByIdAndRoomMemberAndStatusIn(
                        contextId,
                        roomMember,
                        List.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW)
                )
                .orElseThrow(() -> new ForbiddenOperationException("Dispute context is not active for this membership"));

        ensureContextAssignment(dispute.getAssignedAdmin(), currentUser, "Dispute context");
        return new RevealContextInfo(IdentifierRevealContextType.DISPUTE, dispute.getId());
    }

    private void ensureContextAssignment(User assignedUser, User currentUser, String contextLabel) {
        if (assignedUser != null && !assignedUser.getId().equals(currentUser.getId())) {
            throw new ForbiddenOperationException(contextLabel + " is assigned to another staff member");
        }
    }

    private void saveIdentifierRevealAudit(
            Room room,
            RoomMember roomMember,
            User actor,
            String actorRole,
            String eventType,
            String reason,
            RoomMemberIdentifier identifier,
            String contextType,
            Long contextId,
            HttpServletRequest httpRequest
    ) {
        LocalDateTime auditTimestamp = LocalDateTime.now();
        ObjectNode newState = JsonNodeFactory.instance.objectNode();
        newState.put("reason", reason);
        newState.put("identifierType", identifier.getIdentifierType().name());
        newState.put("revealedBy", actorRole);

        if (contextType != null) {
            newState.put("contextType", contextType);
        }

        if (contextId != null) {
            newState.put("contextId", contextId);
        }

        ObjectNode auditNode = newState.putObject("audit");
        auditNode.put("actorId", actor.getId());
        auditNode.put("role", actorRole);
        auditNode.put("reason", reason);
        auditNode.put("roomId", room.getId());
        auditNode.put("memberId", roomMember.getId());
        auditNode.put("timestamp", auditTimestamp.toString());

        if (contextType != null) {
            auditNode.put("contextType", contextType);
        }

        if (contextId != null) {
            auditNode.put("contextId", contextId);
        }

        roomEventLogRepository.save(
                RoomEventLog.builder()
                        .eventId(UUID.randomUUID())
                        .actorUser(actor)
                        .actorRole(actorRole)
                        .room(room)
                        .roomMember(roomMember)
                        .eventType(eventType)
                        .newState(newState)
                        .ipAddress(httpRequest.getRemoteAddr())
                        .userAgent(httpRequest.getHeader("User-Agent"))
                        .createdAt(auditTimestamp)
                        .build()
        );
    }

    private boolean isValidIdentifierFormat(String value) {
        return value != null && value.length() >= 4;
    }

    private record RevealContextInfo(IdentifierRevealContextType type, Long id) {
    }
}
