package kz.hrms.splitupauth.service;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import kz.hrms.splitupauth.dto.*;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.exception.TooManyRequestsException;
import kz.hrms.splitupauth.repository.*;
import kz.hrms.splitupauth.security.FieldEncryptionService;
import kz.hrms.splitupauth.util.ContactIdentifiers;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RoomMemberService {

  private final RoomRepository roomRepository;
  private final RoomMemberRepository roomMemberRepository;
  private final RoomMemberIdentifierRepository roomMemberIdentifierRepository;
  private final RoomMemberMapper roomMemberMapper;
  private final FieldEncryptionService fieldEncryptionService;
  private final PaymentTransactionRepository paymentTransactionRepository;
  private final SupportTicketRepository supportTicketRepository;
  private final ModerationService moderationService;
  private final DisputeRepository disputeRepository;
  private final ModerationQueueRepository moderationQueueRepository;
  private final RoomEventLogger roomEventLogger;
  private final NotificationService notificationService;
  private final IdentifierRevealPolicy identifierRevealPolicy;
  private final IdentifierRevealAuditService identifierRevealAuditService;
  private final InMemoryRateLimiter inMemoryRateLimiter;

  @Value("${app.identifier-reveal.ttl-seconds:30}")
  private long identifierRevealTtlSeconds;

  @Value("${app.rate-limit.identifier-reveal.owner.burst-max:5}")
  private int ownerRevealBurstMax;

  @Value("${app.rate-limit.identifier-reveal.owner.burst-window-seconds:60}")
  private long ownerRevealBurstWindowSeconds;

  @Value("${app.rate-limit.identifier-reveal.owner.daily-max:30}")
  private int ownerRevealDailyMax;

  @Value("${app.rate-limit.identifier-reveal.owner.daily-window-seconds:86400}")
  private long ownerRevealDailyWindowSeconds;

  @Value("${app.rate-limit.identifier-reveal.staff.burst-max:10}")
  private int staffRevealBurstMax;

  @Value("${app.rate-limit.identifier-reveal.staff.burst-window-seconds:60}")
  private long staffRevealBurstWindowSeconds;

  @Value("${app.rate-limit.identifier-reveal.staff.daily-max:80}")
  private int staffRevealDailyMax;

  @Value("${app.rate-limit.identifier-reveal.staff.daily-window-seconds:86400}")
  private long staffRevealDailyWindowSeconds;

  @Transactional
  public RoomMemberDto joinRoom(Long roomId, User currentUser, JoinRoomRequest request) {
    Room room =
        roomRepository
            .findByIdForUpdate(roomId)
            .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

    validateJoin(room, currentUser, request);

    RoomMember roomMember =
        RoomMember.builder()
            .room(room)
            .user(currentUser)
            .status(MemberStatus.APPLIED)
            .requiresAdminReview(false)
            .consentAcceptedAt(LocalDateTime.now())
            .build();

    roomMember = roomMemberRepository.save(roomMember);

    // Every service needs a contact from the member — an address for the ones that
    // invite by email, a number for the ones keyed on the phone (see accessType).
    IdentifierType identifierType = resolveIdentifierType(room, request);
    String normalized = ContactIdentifiers.normalize(identifierType, request.getIdentifierValue());

    RoomMemberIdentifier identifier =
        RoomMemberIdentifier.builder()
            .roomMember(roomMember)
            .identifierType(identifierType)
            .identifierEncrypted(fieldEncryptionService.encrypt(normalized))
            .identifierMasked(ContactIdentifiers.mask(identifierType, normalized))
            .isValidFormat(ContactIdentifiers.isValidFormat(identifierType, normalized))
            .build();

    roomMemberIdentifierRepository.save(identifier);

    roomEventLogger.log(
        room,
        roomMember,
        currentUser,
        "MEMBER",
        "member_joined",
        java.util.Map.of("roomType", String.valueOf(room.getRoomType())));

    // Notify the owner that someone applied to their room.
    notificationService.notify(
        room.getOwner(),
        NotificationType.MEMBER_JOINED,
        "Новая заявка в комнату",
        currentUser.getDisplayName() + " подал(а) заявку в комнату «" + room.getTitle() + "».",
        "/rooms/owner/" + room.getId(),
        java.util.Map.of("roomId", room.getId(), "memberId", roomMember.getId()));

    return roomMemberMapper.toDto(roomMember);
  }

  @Transactional(readOnly = true)
  public PagedResponse<RoomMemberDto> getRoomMembers(
      Long roomId, int page, int size, User currentUser) {
    Room room =
        roomRepository
            .findById(roomId)
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
    Page<RoomMember> resultPage =
        roomMemberRepository.findByRoomAndDeletedAtIsNullOrderByCreatedAtAsc(room, pageable);

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
    Room room =
        roomRepository
            .findById(roomId)
            .filter(r -> r.getDeletedAt() == null)
            .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

    RoomMember roomMember =
        roomMemberRepository
            .findByRoomAndUserAndDeletedAtIsNull(room, currentUser)
            .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));

    RoomMemberIdentifier identifier =
        roomMemberIdentifierRepository.findByRoomMember(roomMember).orElse(null);

    return roomMemberMapper.toMyDto(roomMember, identifier);
  }

  @Transactional(readOnly = true)
  public List<JoinedRoomDto> getMyJoinedRooms(User currentUser) {
    return roomMemberRepository
        .findByUserAndDeletedAtIsNullOrderByCreatedAtDesc(currentUser)
        .stream()
        .filter(member -> member.getRoom() != null && member.getRoom().getDeletedAt() == null)
        .map(
            member -> {
              Room room = member.getRoom();
              return JoinedRoomDto.builder()
                  .roomId(room.getId())
                  .memberId(member.getId())
                  .title(room.getTitle())
                  .roomType(room.getRoomType())
                  .roomStatus(room.getStatus())
                  .memberStatus(member.getStatus())
                  .requiresAdminReview(member.getRequiresAdminReview())
                  .maxMembers(room.getMaxMembers())
                  .priceTotal(room.getPriceTotal())
                  .pricePerMember(room.getPricePerMember())
                  .currency(room.getCurrency())
                  .startDate(room.getStartDate())
                  .ownerUserId(room.getOwner().getId())
                  .ownerDisplayName(room.getOwner().getDisplayName())
                  .serviceId(room.getService().getId())
                  .serviceName(room.getService().getName())
                  .build();
            })
        .toList();
  }

  @Transactional
  public RoomMemberDto confirmOwnerAccess(
      Long roomId, Long memberId, User currentUser, ConfirmOwnerAccessRequest request) {
    Room room =
        roomRepository
            .findById(roomId)
            .filter(r -> r.getDeletedAt() == null)
            .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

    if (!room.getOwner().getId().equals(currentUser.getId())) {
      throw new ForbiddenOperationException("Only room owner can confirm access");
    }

    RoomMember roomMember =
        roomMemberRepository
            .findByIdAndRoomAndDeletedAtIsNull(memberId, room)
            .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));

    if (roomMember.getStatus() != MemberStatus.PENDING) {
      throw new InvalidRequestException("Access can only be confirmed for PENDING membership");
    }

    if (roomMember.getOwnerAccessConfirmedAt() == null) {
      roomMember.setOwnerAccessConfirmedAt(LocalDateTime.now());
    }

    roomMember.setAccessMethod(request.getAccessMethod());

    roomEventLogger.log(
        room,
        roomMember,
        currentUser,
        "OWNER",
        "owner_access_granted",
        java.util.Map.of("accessMethod", String.valueOf(request.getAccessMethod())));

    // Notify the member that the owner granted access — their cue to confirm.
    notificationService.notify(
        roomMember.getUser(),
        NotificationType.OWNER_ACCESS_GRANTED,
        "Доступ предоставлен",
        "Владелец комнаты «"
            + room.getTitle()
            + "» предоставил доступ. Подтвердите получение доступа.",
        "/rooms/member/" + room.getId(),
        java.util.Map.of("roomId", room.getId(), "memberId", roomMember.getId()));

    tryActivateMembership(roomMember);

    roomMemberRepository.save(roomMember);

    return roomMemberMapper.toDto(roomMember);
  }

  @Transactional
  public MyRoomMembershipDto confirmMemberAccess(Long roomId, User currentUser) {
    Room room =
        roomRepository
            .findById(roomId)
            .filter(r -> r.getDeletedAt() == null)
            .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

    RoomMember roomMember =
        roomMemberRepository
            .findByRoomAndUserAndDeletedAtIsNull(room, currentUser)
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

    roomEventLogger.log(
        room, roomMember, currentUser, "MEMBER", "member_confirmed", java.util.Map.of());

    // Notify the owner that the member confirmed they received access.
    notificationService.notify(
        room.getOwner(),
        NotificationType.MEMBER_CONFIRMED,
        "Участник подтвердил доступ",
        currentUser.getDisplayName()
            + " подтвердил(а) получение доступа в комнате «"
            + room.getTitle()
            + "».",
        "/rooms/owner/" + room.getId(),
        java.util.Map.of("roomId", room.getId(), "memberId", roomMember.getId()));

    tryActivateMembership(roomMember);

    roomMemberRepository.save(roomMember);

    if (roomMember.getStatus() == MemberStatus.ACTIVE) {
      roomEventLogger.log(
          room, roomMember, currentUser, "MEMBER", "membership_activated", java.util.Map.of());
    }

    RoomMemberIdentifier identifier =
        roomMemberIdentifierRepository.findByRoomMember(roomMember).orElse(null);

    return roomMemberMapper.toMyDto(roomMember, identifier);
  }

  @Transactional
  public void markMembershipAsPaid(RoomMember roomMember) {
    if (roomMember.getDeletedAt() != null) {
      throw new ResourceNotFoundException("Membership not found");
    }

    if (roomMember.getStatus() == MemberStatus.PENDING
        || roomMember.getStatus() == MemberStatus.ACTIVE) {
      return;
    }

    if (roomMember.getStatus() != MemberStatus.APPLIED) {
      throw new InvalidRequestException("Only APPLIED membership can be marked as paid");
    }

    roomMember.setStatus(MemberStatus.PENDING);

    boolean requiresAdminReview = shouldRequireAdminReviewAfterPayment(roomMember);
    roomMember.setRequiresAdminReview(requiresAdminReview);

    roomMemberRepository.save(roomMember);

    notifyOwnerOfMemberPayment(roomMember);

    if (requiresAdminReview) {
      moderationService.enqueueMembershipForReview(
          roomMember, resolveModerationReasonCode(roomMember), java.math.BigDecimal.ZERO);
    } else {
      tryActivateMembership(roomMember);
      roomMemberRepository.save(roomMember);
    }
  }

  /**
   * On a member's payment (APPLIED → PENDING): tell the owner that member has paid and is now
   * requesting connection. If this payment filled the last free seat, additionally emit the major
   * "room full — everyone has paid" notification, which is email-eligible so the owner is emailed.
   */
  private void notifyOwnerOfMemberPayment(RoomMember roomMember) {
    Room room = roomMember.getRoom();
    User owner = room.getOwner();
    String memberName = roomMember.getUser().getDisplayName();

    notificationService.notify(
        owner,
        NotificationType.ROOM_MEMBER_PAID,
        "Участник оплатил",
        "Участник "
            + memberName
            + " оплатил участие в комнате «"
            + room.getTitle()
            + "» и ожидает подключения. Предоставьте доступ.",
        "/rooms/owner/" + room.getId(),
        java.util.Map.of("roomId", room.getId(), "memberId", roomMember.getId()));

    // A room's member seats = maxMembers - 1 (the owner holds one). When the paid
    // members reach that count, every seat is filled and awaiting access.
    long occupiedSlots =
        roomMemberRepository.countByRoomAndStatusInAndDeletedAtIsNull(
            room, List.of(MemberStatus.PENDING, MemberStatus.ACTIVE));
    if (occupiedSlots >= room.getMaxMembers() - 1) {
      notificationService.notify(
          owner,
          NotificationType.ROOM_FULL_AWAITING_ACCESS,
          "Комната заполнена",
          "Все участники комнаты «"
              + room.getTitle()
              + "» оплатили и ожидают подключения. Предоставьте доступ каждому.",
          "/rooms/owner/" + room.getId(),
          java.util.Map.of("roomId", room.getId()));
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

    RoomMemberIdentifier identifier =
        roomMemberIdentifierRepository.findByRoomMember(roomMember).orElse(null);

    // A malformed contact means the owner cannot actually grant access, so hold the
    // member for review. Missing rows are only tolerated for the digital memberships
    // that predate contact collection — TELECOM always required one.
    if (identifier == null) {
      if (roomMember.getRoom().getRoomType() == RoomType.TELECOM) {
        return;
      }
    } else if (!Boolean.TRUE.equals(identifier.getIsValidFormat())) {
      return;
    }

    roomMember.setStatus(MemberStatus.ACTIVE);

    if (roomMember.getActivatedAt() == null) {
      roomMember.setActivatedAt(LocalDateTime.now());
    }

    // Product rule: a room goes ACTIVE as soon as it has its first ACTIVE member.
    // (Without this the room never left OPEN/IN_VERIFICATION → reviews and
    // completion were unreachable.)
    Room room = roomMember.getRoom();
    boolean roomBecameActive = false;
    if (room.getStatus() == RoomStatus.OPEN || room.getStatus() == RoomStatus.IN_VERIFICATION) {
      room.setStatus(RoomStatus.ACTIVE);
      roomRepository.save(room);
      roomBecameActive = true;
    }

    // Central activation point — fired from every path that can flip a
    // membership to ACTIVE (owner-confirm, member-confirm, paid-no-review).
    notificationService.notify(
        roomMember.getUser(),
        NotificationType.MEMBERSHIP_ACTIVATED,
        "Участие активировано",
        "Ваше участие в комнате «" + room.getTitle() + "» активно. Доступ подтверждён.",
        "/rooms/member/" + room.getId(),
        java.util.Map.of("roomId", room.getId(), "memberId", roomMember.getId()));

    if (roomBecameActive) {
      notificationService.notify(
          room.getOwner(),
          NotificationType.ROOM_ACTIVE,
          "Комната активна",
          "Комната «" + room.getTitle() + "» перешла в статус «Активна».",
          "/rooms/owner/" + room.getId(),
          java.util.Map.of("roomId", room.getId()));
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

    roomMemberRepository
        .findByRoomAndUserAndDeletedAtIsNull(room, currentUser)
        .ifPresent(
            existing -> {
              throw new InvalidRequestException("User has already joined this room");
            });

    long occupiedSlots =
        roomMemberRepository.countByRoomAndStatusInAndDeletedAtIsNull(
            room, List.of(MemberStatus.PENDING, MemberStatus.ACTIVE));

    if (occupiedSlots >= room.getMaxMembers() - 1) {
      throw new InvalidRequestException("No available slots in this room");
    }

    validateContact(room, request);
  }

  /**
   * The service's {@code accessType} decides what the member has to hand over: an email for the
   * providers that invite by address, a phone for the ones keyed on the number, either when the
   * provider accepts both. TELECOM rooms additionally accept SIM/eSIM/account references.
   */
  private void validateContact(Room room, JoinRoomRequest request) {
    ServiceAccessType accessType = serviceAccessType(room);
    IdentifierType type = resolveIdentifierType(room, request);

    if (!ContactIdentifiers.allowedFor(accessType).contains(type)) {
      throw new InvalidRequestException(
          accessType == ServiceAccessType.EMAIL
              ? "This service grants access by email — an email address is required"
              : "This service grants access by phone — a phone number is required");
    }

    String normalized = ContactIdentifiers.normalize(type, request.getIdentifierValue());
    if (normalized == null) {
      throw new InvalidRequestException(
          type == IdentifierType.EMAIL
              ? "Email address is required to join this room"
              : "Phone number is required to join this room");
    }

    if (!ContactIdentifiers.isValidFormat(type, normalized)) {
      throw new InvalidRequestException(
          switch (type) {
            case EMAIL -> "Enter a valid email address";
            case ACCOUNT -> "Enter a valid account identifier";
            default -> "Phone must be in +7XXXXXXXXXX format";
          });
    }
  }

  /** Falls back to the service's natural identifier when the client didn't send an explicit one. */
  private IdentifierType resolveIdentifierType(Room room, JoinRoomRequest request) {
    return request.getIdentifierType() != null
        ? request.getIdentifierType()
        : ContactIdentifiers.defaultFor(serviceAccessType(room));
  }

  private ServiceAccessType serviceAccessType(Room room) {
    ServiceAccessType accessType =
        room.getService() == null ? null : room.getService().getAccessType();
    if (accessType != null) {
      return accessType;
    }
    // Pre-V54 rows could still be null in a half-migrated environment; a telecom
    // room is always phone-shaped, so that is the safe read.
    return room.getRoomType() == RoomType.TELECOM
        ? ServiceAccessType.PHONE
        : ServiceAccessType.EMAIL;
  }

  @Transactional
  public RevealedIdentifierDto revealIdentifierForOwner(
      Long roomId,
      Long memberId,
      User currentUser,
      RevealIdentifierRequest request,
      HttpServletRequest httpRequest) {
    Room room =
        roomRepository
            .findById(roomId)
            .filter(r -> r.getDeletedAt() == null)
            .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

    RoomMember roomMember =
        roomMemberRepository
            .findByIdAndRoomAndDeletedAtIsNull(memberId, room)
            .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));

    try {
      identifierRevealPolicy.canOwnerReveal(room, roomMember, currentUser, request);
      identifierRevealPolicy.ensureValidSuccessfulPayment(roomMember);
      checkRevealRateLimit(
          "owner", currentUser, roomMember, room, request, null, null, httpRequest);
    } catch (TooManyRequestsException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      auditRevealAttempt(
          room,
          roomMember,
          currentUser,
          "OWNER",
          null,
          null,
          request,
          IdentifierRevealOutcome.DENIED,
          httpRequest);
      throw ex;
    }

    RoomMemberIdentifier identifier =
        roomMemberIdentifierRepository
            .findByRoomMember(roomMember)
            .orElseThrow(() -> new ResourceNotFoundException("Identifier not found"));

    String decryptedIdentifier =
        decryptIdentifier(
            room, roomMember, currentUser, "OWNER", request, null, null, identifier, httpRequest);
    auditRevealAttempt(
        room,
        roomMember,
        currentUser,
        "OWNER",
        null,
        null,
        request,
        IdentifierRevealOutcome.SUCCESS,
        httpRequest);

    return RevealedIdentifierDto.builder()
        .roomId(room.getId())
        .roomMemberId(roomMember.getId())
        .identifierType(identifier.getIdentifierType().name())
        .identifierValue(decryptedIdentifier)
        .revealTtlSeconds(identifierRevealTtlSeconds)
        .build();
  }

  @Transactional
  public RevealedIdentifierDto revealIdentifierForStaff(
      Long roomId,
      Long memberId,
      User currentUser,
      RevealIdentifierRequest request,
      HttpServletRequest httpRequest) {
    ensureStaffCanReveal(currentUser);

    Room room =
        roomRepository
            .findById(roomId)
            .filter(r -> r.getDeletedAt() == null)
            .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

    RoomMember roomMember =
        roomMemberRepository
            .findByIdAndRoomAndDeletedAtIsNull(memberId, room)
            .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));

    RevealContextInfo revealContext = null;
    try {
      revealContext = resolveRevealContext(roomMember, currentUser, request);
      identifierRevealPolicy.canStaffReveal(roomMember, request, revealContext.type());
      identifierRevealPolicy.ensureValidSuccessfulPayment(roomMember);
      checkRevealRateLimit(
          "staff",
          currentUser,
          roomMember,
          room,
          request,
          revealContext.type(),
          revealContext.id(),
          httpRequest);
    } catch (TooManyRequestsException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      auditRevealAttempt(
          room,
          roomMember,
          currentUser,
          currentUser.getRole().name(),
          revealContext == null ? null : revealContext.type(),
          revealContext == null ? null : revealContext.id(),
          request,
          IdentifierRevealOutcome.DENIED,
          httpRequest);
      throw ex;
    }

    RoomMemberIdentifier identifier =
        roomMemberIdentifierRepository
            .findByRoomMember(roomMember)
            .orElseThrow(() -> new ResourceNotFoundException("Identifier not found"));

    String decryptedIdentifier =
        decryptIdentifier(
            room,
            roomMember,
            currentUser,
            currentUser.getRole().name(),
            request,
            revealContext.type(),
            revealContext.id(),
            identifier,
            httpRequest);
    auditRevealAttempt(
        room,
        roomMember,
        currentUser,
        currentUser.getRole().name(),
        revealContext.type(),
        revealContext.id(),
        request,
        IdentifierRevealOutcome.SUCCESS,
        httpRequest);

    return RevealedIdentifierDto.builder()
        .roomId(room.getId())
        .roomMemberId(roomMember.getId())
        .identifierType(identifier.getIdentifierType().name())
        .identifierValue(decryptedIdentifier)
        .revealTtlSeconds(identifierRevealTtlSeconds)
        .build();
  }

  private boolean shouldRequireAdminReviewAfterPayment(RoomMember roomMember) {
    Room room = roomMember.getRoom();

    if (room.getVerificationMode() == VerificationMode.ADMIN_REQUIRED) {
      return true;
    }

    if (room.getVerificationMode() == VerificationMode.RISK_BASED) {
      if (hasMalformedContact(roomMember)) {
        return true;
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

    if (hasMalformedContact(roomMember)) {
      return "INVALID_IDENTIFIER";
    }

    if (hasOpenDispute(roomMember)) {
      return "OPEN_DISPUTE";
    }

    if (hasOpenSupportTicket(roomMember)) {
      return "SUPPORT_TICKET";
    }

    return "RISK_REVIEW";
  }

  private boolean hasOpenSupportTicket(RoomMember roomMember) {
    return supportTicketRepository.existsByRoomMemberAndStatusIn(
        roomMember,
        List.of(
            SupportTicketStatus.OPEN,
            SupportTicketStatus.IN_PROGRESS,
            SupportTicketStatus.WAITING_USER,
            SupportTicketStatus.ESCALATED));
  }

  private boolean hasOpenDispute(RoomMember roomMember) {
    return disputeRepository.existsByRoomMemberAndStatusIn(
        roomMember, List.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW));
  }

  private boolean hasOpenAutoActivationBlocker(RoomMember roomMember) {
    return hasOpenSupportTicket(roomMember) || hasOpenDispute(roomMember);
  }

  /**
   * True when the member's contact can't be used to grant access. A missing row only counts against
   * TELECOM rooms — digital memberships created before contact collection have none.
   */
  private boolean hasMalformedContact(RoomMember roomMember) {
    RoomMemberIdentifier identifier =
        roomMemberIdentifierRepository.findByRoomMember(roomMember).orElse(null);
    if (identifier == null) {
      return roomMember.getRoom().getRoomType() == RoomType.TELECOM;
    }
    return !Boolean.TRUE.equals(identifier.getIsValidFormat());
  }

  private void ensureStaffCanReveal(User currentUser) {
    if (currentUser == null
        || (currentUser.getRole() != Role.ADMIN && currentUser.getRole() != Role.SUPPORT)) {
      throw new ForbiddenOperationException("Support or admin access required");
    }
  }

  private RevealContextInfo resolveRevealContext(
      RoomMember roomMember, User currentUser, RevealIdentifierRequest request) {
    if (request.getContextType() == null
        || request.getContextType().isBlank()
        || request.getContextId() == null) {
      throw new ForbiddenOperationException(
          "Admin/Support can reveal identifier only with moderation, support, or dispute context");
    }

    IdentifierRevealContextType contextType;
    try {
      contextType =
          IdentifierRevealContextType.valueOf(request.getContextType().trim().toUpperCase());
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

  private RevealContextInfo resolveModerationContext(
      RoomMember roomMember, User currentUser, Long contextId) {
    if (currentUser.getRole() != Role.ADMIN) {
      throw new ForbiddenOperationException(
          "Only admin can reveal identifier in moderation context");
    }

    ModerationQueue item =
        moderationQueueRepository
            .findByIdAndRoomMemberAndStatusIn(
                contextId,
                roomMember,
                List.of(ModerationQueueStatus.OPEN, ModerationQueueStatus.IN_REVIEW))
            .orElseThrow(
                () ->
                    new ForbiddenOperationException(
                        "Moderation context is not active for this membership"));

    ensureContextAssignment(item.getAssignedAdmin(), currentUser, "Moderation context");
    return new RevealContextInfo(IdentifierRevealContextType.MODERATION, item.getId());
  }

  private RevealContextInfo resolveSupportContext(
      RoomMember roomMember, User currentUser, Long contextId) {
    SupportTicket ticket =
        supportTicketRepository
            .findByIdAndRoomMemberAndStatusIn(
                contextId,
                roomMember,
                List.of(
                    SupportTicketStatus.OPEN,
                    SupportTicketStatus.IN_PROGRESS,
                    SupportTicketStatus.WAITING_USER,
                    SupportTicketStatus.ESCALATED))
            .orElseThrow(
                () ->
                    new ForbiddenOperationException(
                        "Support context is not active for this membership"));

    ensureContextAssignment(ticket.getAssignedAdmin(), currentUser, "Support context");
    return new RevealContextInfo(IdentifierRevealContextType.SUPPORT, ticket.getId());
  }

  private RevealContextInfo resolveDisputeContext(
      RoomMember roomMember, User currentUser, Long contextId) {
    Dispute dispute =
        disputeRepository
            .findByIdAndRoomMemberAndStatusIn(
                contextId, roomMember, List.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW))
            .orElseThrow(
                () ->
                    new ForbiddenOperationException(
                        "Dispute context is not active for this membership"));

    ensureContextAssignment(dispute.getAssignedAdmin(), currentUser, "Dispute context");
    return new RevealContextInfo(IdentifierRevealContextType.DISPUTE, dispute.getId());
  }

  private void ensureContextAssignment(User assignedUser, User currentUser, String contextLabel) {
    if (assignedUser == null) {
      throw new ForbiddenOperationException(contextLabel + " must be assigned before reveal");
    }
    if (!assignedUser.getId().equals(currentUser.getId())) {
      throw new ForbiddenOperationException(contextLabel + " is assigned to another staff member");
    }
  }

  private void checkRevealRateLimit(
      String actorBucket,
      User actor,
      RoomMember roomMember,
      Room room,
      RevealIdentifierRequest request,
      IdentifierRevealContextType contextType,
      Long contextId,
      HttpServletRequest httpRequest) {
    int burstMax = actorBucket.equals("staff") ? staffRevealBurstMax : ownerRevealBurstMax;
    long burstWindow =
        actorBucket.equals("staff") ? staffRevealBurstWindowSeconds : ownerRevealBurstWindowSeconds;
    int dailyMax = actorBucket.equals("staff") ? staffRevealDailyMax : ownerRevealDailyMax;
    long dailyWindow =
        actorBucket.equals("staff") ? staffRevealDailyWindowSeconds : ownerRevealDailyWindowSeconds;

    try {
      if (burstMax > 0) {
        inMemoryRateLimiter.check(
            "identifier-reveal:" + actorBucket + ":burst:actor:" + actor.getId(),
            burstMax,
            burstWindow,
            "Too many identifier reveal attempts. Try again later.");
        inMemoryRateLimiter.check(
            "identifier-reveal:" + actorBucket + ":burst:member:" + roomMember.getId(),
            burstMax,
            burstWindow,
            "Too many identifier reveal attempts. Try again later.");
      }
      if (dailyMax > 0) {
        inMemoryRateLimiter.check(
            "identifier-reveal:" + actorBucket + ":daily:actor:" + actor.getId(),
            dailyMax,
            dailyWindow,
            "Too many identifier reveal attempts. Try again later.");
        inMemoryRateLimiter.check(
            "identifier-reveal:" + actorBucket + ":daily:member:" + roomMember.getId(),
            dailyMax,
            dailyWindow,
            "Too many identifier reveal attempts. Try again later.");
      }
    } catch (TooManyRequestsException ex) {
      auditRevealAttempt(
          room,
          roomMember,
          actor,
          actorBucket.equals("staff") ? actor.getRole().name() : "OWNER",
          contextType,
          contextId,
          request,
          IdentifierRevealOutcome.RATE_LIMITED,
          httpRequest);
      throw ex;
    }
  }

  private String decryptIdentifier(
      Room room,
      RoomMember roomMember,
      User actor,
      String actorRole,
      RevealIdentifierRequest request,
      IdentifierRevealContextType contextType,
      Long contextId,
      RoomMemberIdentifier identifier,
      HttpServletRequest httpRequest) {
    try {
      return fieldEncryptionService.decrypt(identifier.getIdentifierEncrypted());
    } catch (RuntimeException ex) {
      auditRevealAttempt(
          room,
          roomMember,
          actor,
          actorRole,
          contextType,
          contextId,
          request,
          IdentifierRevealOutcome.DECRYPTION_FAILED,
          httpRequest);
      throw ex;
    }
  }

  private void auditRevealAttempt(
      Room room,
      RoomMember roomMember,
      User actor,
      String actorRole,
      IdentifierRevealContextType contextType,
      Long contextId,
      RevealIdentifierRequest request,
      IdentifierRevealOutcome outcome,
      HttpServletRequest httpRequest) {
    IdentifierRevealReasonCode reasonCode =
        request.getReasonCode() == null
            ? IdentifierRevealReasonCode.ACCESS_ISSUE
            : request.getReasonCode();
    identifierRevealAuditService.record(
        room,
        roomMember,
        actor,
        actorRole,
        contextType,
        contextId,
        reasonCode,
        outcome,
        httpRequest);
  }

  private record RevealContextInfo(IdentifierRevealContextType type, Long id) {}
}
