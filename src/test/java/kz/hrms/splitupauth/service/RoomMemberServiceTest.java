package kz.hrms.splitupauth.service;

import jakarta.servlet.http.HttpServletRequest;
import kz.hrms.splitupauth.dto.JoinRoomRequest;
import kz.hrms.splitupauth.dto.MyRoomMembershipDto;
import kz.hrms.splitupauth.dto.RevealIdentifierRequest;
import kz.hrms.splitupauth.dto.RevealedIdentifierDto;
import kz.hrms.splitupauth.dto.RoomMemberDto;
import kz.hrms.splitupauth.entity.Dispute;
import kz.hrms.splitupauth.entity.DisputeStatus;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.ModerationQueue;
import kz.hrms.splitupauth.entity.ModerationQueueStatus;
import kz.hrms.splitupauth.entity.PaymentTransactionStatus;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomEventLog;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.RoomMemberIdentifier;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.RoomType;
import kz.hrms.splitupauth.entity.SupportTicket;
import kz.hrms.splitupauth.entity.SupportTicketStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.entity.VerificationMode;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.repository.DisputeRepository;
import kz.hrms.splitupauth.repository.ModerationQueueRepository;
import kz.hrms.splitupauth.repository.PaymentTransactionRepository;
import kz.hrms.splitupauth.repository.RoomEventLogRepository;
import kz.hrms.splitupauth.repository.RoomMemberIdentifierRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.SupportTicketRepository;
import kz.hrms.splitupauth.security.FieldEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomMemberServiceTest {

    private static final long ROOM_ID = 10L;

    @Mock
    private RoomRepository roomRepository;
    @Mock
    private RoomMemberRepository roomMemberRepository;
    @Mock
    private RoomMemberIdentifierRepository roomMemberIdentifierRepository;
    @Mock
    private RoomMemberMapper roomMemberMapper;
    @Mock
    private FieldEncryptionService fieldEncryptionService;
    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;
    @Mock
    private RoomEventLogRepository roomEventLogRepository;
    @Mock
    private SupportTicketRepository supportTicketRepository;
    @Mock
    private ModerationService moderationService;
    @Mock
    private DisputeRepository disputeRepository;
    @Mock
    private ModerationQueueRepository moderationQueueRepository;

    private RoomMemberService roomMemberService;

    @BeforeEach
    void setUp() {
        roomMemberService = new RoomMemberService(
                roomRepository,
                roomMemberRepository,
                roomMemberIdentifierRepository,
                roomMemberMapper,
                fieldEncryptionService,
                paymentTransactionRepository,
                roomEventLogRepository,
                supportTicketRepository,
                moderationService,
                disputeRepository,
                moderationQueueRepository
        );

        lenient().when(roomMemberRepository.save(any(RoomMember.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(roomEventLogRepository.save(any(RoomEventLog.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(roomMemberIdentifierRepository.findByRoomMember(any(RoomMember.class)))
                .thenReturn(Optional.empty());
        lenient().when(roomMemberMapper.toDto(any(RoomMember.class)))
                .thenAnswer(invocation -> {
                    RoomMember roomMember = invocation.getArgument(0);
                    return RoomMemberDto.builder()
                            .id(roomMember.getId())
                            .status(roomMember.getStatus())
                            .requiresAdminReview(roomMember.getRequiresAdminReview())
                            .consentAcceptedAt(roomMember.getConsentAcceptedAt())
                            .build();
                });
        lenient().when(roomMemberMapper.toMyDto(any(RoomMember.class), nullable(RoomMemberIdentifier.class)))
                .thenAnswer(invocation -> {
                    RoomMember roomMember = invocation.getArgument(0);
                    return MyRoomMembershipDto.builder()
                            .id(roomMember.getId())
                            .status(roomMember.getStatus())
                            .requiresAdminReview(roomMember.getRequiresAdminReview())
                            .ownerAccessConfirmedAt(roomMember.getOwnerAccessConfirmedAt())
                            .memberConfirmedAt(roomMember.getMemberConfirmedAt())
                            .activatedAt(roomMember.getActivatedAt())
                            .build();
                });
    }

    @Test
    void confirmMemberAccess_doesNotActivate_whenOpenSupportTicketExists() {
        User member = user(200L, Role.USER);
        Room room = room(100L);
        RoomMember roomMember = pendingMembership(300L, room, member);

        mockConfirmMemberAccessLookup(room, member, roomMember);
        when(supportTicketRepository.existsByRoomMemberAndStatusIn(eq(roomMember), anyList())).thenReturn(true);

        MyRoomMembershipDto result = roomMemberService.confirmMemberAccess(ROOM_ID, member);

        assertEquals(MemberStatus.PENDING, roomMember.getStatus());
        assertNull(roomMember.getActivatedAt());
        assertNotNull(roomMember.getMemberConfirmedAt());
        assertEquals(MemberStatus.PENDING, result.getStatus());
    }

    @Test
    void confirmMemberAccess_doesNotActivate_whenOpenDisputeExists() {
        User member = user(201L, Role.USER);
        Room room = room(101L);
        RoomMember roomMember = pendingMembership(301L, room, member);

        mockConfirmMemberAccessLookup(room, member, roomMember);
        when(disputeRepository.existsByRoomMemberAndStatusIn(eq(roomMember), anyList())).thenReturn(true);

        MyRoomMembershipDto result = roomMemberService.confirmMemberAccess(ROOM_ID, member);

        assertEquals(MemberStatus.PENDING, roomMember.getStatus());
        assertNull(roomMember.getActivatedAt());
        assertNotNull(roomMember.getMemberConfirmedAt());
        assertEquals(MemberStatus.PENDING, result.getStatus());
    }

    @Test
    void confirmMemberAccess_activates_whenNoOpenSupportOrDisputeAndOtherConditionsMet() {
        User member = user(202L, Role.USER);
        Room room = room(102L);
        RoomMember roomMember = pendingMembership(302L, room, member);

        mockConfirmMemberAccessLookup(room, member, roomMember);

        MyRoomMembershipDto result = roomMemberService.confirmMemberAccess(ROOM_ID, member);

        assertEquals(MemberStatus.ACTIVE, roomMember.getStatus());
        assertNotNull(roomMember.getActivatedAt());
        assertNotNull(roomMember.getMemberConfirmedAt());
        assertEquals(MemberStatus.ACTIVE, result.getStatus());
    }

    @Test
    void joinRoom_rejectsAfterStartDate() {
        User member = user(203L, Role.USER);
        Room room = room(103L);
        room.setStartDate(LocalDateTime.now().minusMinutes(1));

        JoinRoomRequest request = new JoinRoomRequest();
        request.setConsentAccepted(true);

        when(roomRepository.findByIdForUpdate(ROOM_ID)).thenReturn(Optional.of(room));

        InvalidRequestException exception = assertThrows(
                InvalidRequestException.class,
                () -> roomMemberService.joinRoom(ROOM_ID, member, request)
        );

        assertEquals("Cannot join room after start date", exception.getMessage());
        verify(roomMemberRepository, never()).save(any(RoomMember.class));
    }

    @Test
    void revealIdentifierForOwner_rejectsBeforeSuccessfulPayment() {
        User owner = user(300L, Role.USER);
        Room room = telecomRoom(400L, owner);
        RoomMember roomMember = membership(500L, room, user(301L, Role.USER));
        RevealIdentifierRequest request = revealRequest("Owner support check", null, null);

        when(roomRepository.findById(room.getId())).thenReturn(Optional.of(room));
        when(roomMemberRepository.findByIdAndRoomAndDeletedAtIsNull(roomMember.getId(), room))
                .thenReturn(Optional.of(roomMember));
        when(paymentTransactionRepository.existsByRoomMember_IdAndStatus(roomMember.getId(), PaymentTransactionStatus.SUCCESS))
                .thenReturn(false);

        ForbiddenOperationException exception = assertThrows(
                ForbiddenOperationException.class,
                () -> roomMemberService.revealIdentifierForOwner(
                        room.getId(),
                        roomMember.getId(),
                        owner,
                        request,
                        httpRequest()
                )
        );

        assertEquals("Identifier can only be revealed after successful payment", exception.getMessage());
        verify(roomEventLogRepository, never()).save(any(RoomEventLog.class));
    }

    @Test
    void revealIdentifierForOwner_logsAuditAfterSuccessfulPayment() {
        User owner = user(302L, Role.USER);
        Room room = telecomRoom(401L, owner);
        RoomMember roomMember = membership(501L, room, user(303L, Role.USER));
        RoomMemberIdentifier identifier = identifier(roomMember);
        RevealIdentifierRequest request = revealRequest("Owner payment verified", null, null);

        when(roomRepository.findById(room.getId())).thenReturn(Optional.of(room));
        when(roomMemberRepository.findByIdAndRoomAndDeletedAtIsNull(roomMember.getId(), room))
                .thenReturn(Optional.of(roomMember));
        when(paymentTransactionRepository.existsByRoomMember_IdAndStatus(roomMember.getId(), PaymentTransactionStatus.SUCCESS))
                .thenReturn(true);
        when(roomMemberIdentifierRepository.findByRoomMember(roomMember)).thenReturn(Optional.of(identifier));
        when(fieldEncryptionService.decrypt(identifier.getIdentifierEncrypted())).thenReturn("+77001234567");

        RevealedIdentifierDto result = roomMemberService.revealIdentifierForOwner(
                room.getId(),
                roomMember.getId(),
                owner,
                request,
                httpRequest()
        );

        assertEquals("+77001234567", result.getIdentifierValue());

        ArgumentCaptor<RoomEventLog> logCaptor = ArgumentCaptor.forClass(RoomEventLog.class);
        verify(roomEventLogRepository).save(logCaptor.capture());
        RoomEventLog log = logCaptor.getValue();

        assertEquals("OWNER", log.getActorRole());
        assertEquals(room.getId(), log.getRoom().getId());
        assertEquals(roomMember.getId(), log.getRoomMember().getId());
        assertEquals("Owner payment verified", log.getNewState().get("reason").asText());
        assertEquals("OWNER", log.getNewState().get("audit").get("role").asText());
        assertEquals(roomMember.getId(), log.getNewState().get("audit").get("memberId").asLong());
        assertNotNull(log.getCreatedAt());
    }

    @Test
    void revealIdentifierForStaff_rejectsWithoutExplicitContext() {
        User support = user(304L, Role.SUPPORT);
        Room room = telecomRoom(402L, user(305L, Role.USER));
        RoomMember roomMember = membership(502L, room, user(306L, Role.USER));
        RevealIdentifierRequest request = revealRequest("Need review", null, null);

        when(roomRepository.findById(room.getId())).thenReturn(Optional.of(room));
        when(roomMemberRepository.findByIdAndRoomAndDeletedAtIsNull(roomMember.getId(), room))
                .thenReturn(Optional.of(roomMember));
        when(paymentTransactionRepository.existsByRoomMember_IdAndStatus(roomMember.getId(), PaymentTransactionStatus.SUCCESS))
                .thenReturn(true);

        ForbiddenOperationException exception = assertThrows(
                ForbiddenOperationException.class,
                () -> roomMemberService.revealIdentifierForStaff(
                        room.getId(),
                        roomMember.getId(),
                        support,
                        request,
                        httpRequest()
                )
        );

        assertEquals(
                "Admin/Support can reveal identifier only with moderation, support, or dispute context",
                exception.getMessage()
        );
        verify(roomEventLogRepository, never()).save(any(RoomEventLog.class));
    }

    @Test
    void revealIdentifierForStaff_allowsSupportContextAndLogsAudit() {
        User support = user(307L, Role.SUPPORT);
        Room room = telecomRoom(403L, user(308L, Role.USER));
        RoomMember roomMember = membership(503L, room, user(309L, Role.USER));
        RoomMemberIdentifier identifier = identifier(roomMember);
        SupportTicket ticket = SupportTicket.builder()
                .id(601L)
                .room(room)
                .roomMember(roomMember)
                .user(roomMember.getUser())
                .subject("Identifier check")
                .topic("ACCESS_ISSUE")
                .status(SupportTicketStatus.IN_PROGRESS)
                .assignedAdmin(support)
                .build();
        RevealIdentifierRequest request = revealRequest("Active support ticket", "support", ticket.getId());

        when(roomRepository.findById(room.getId())).thenReturn(Optional.of(room));
        when(roomMemberRepository.findByIdAndRoomAndDeletedAtIsNull(roomMember.getId(), room))
                .thenReturn(Optional.of(roomMember));
        when(paymentTransactionRepository.existsByRoomMember_IdAndStatus(roomMember.getId(), PaymentTransactionStatus.SUCCESS))
                .thenReturn(true);
        when(supportTicketRepository.findByIdAndRoomMemberAndStatusIn(eq(ticket.getId()), eq(roomMember), anyList()))
                .thenReturn(Optional.of(ticket));
        when(roomMemberIdentifierRepository.findByRoomMember(roomMember)).thenReturn(Optional.of(identifier));
        when(fieldEncryptionService.decrypt(identifier.getIdentifierEncrypted())).thenReturn("+77011234567");

        RevealedIdentifierDto result = roomMemberService.revealIdentifierForStaff(
                room.getId(),
                roomMember.getId(),
                support,
                request,
                httpRequest()
        );

        assertEquals("+77011234567", result.getIdentifierValue());
        verify(roomEventLogRepository).save(argThat(log ->
                "SUPPORT".equals(log.getActorRole())
                        && "SUPPORT".equals(log.getNewState().get("contextType").asText())
                        && log.getNewState().get("contextId").asLong() == ticket.getId()
                        && "Active support ticket".equals(log.getNewState().get("audit").get("reason").asText())
        ));
    }

    @Test
    void revealIdentifierForStaff_allowsModerationContextForAdmin() {
        User admin = user(310L, Role.ADMIN);
        Room room = telecomRoom(404L, user(311L, Role.USER));
        RoomMember roomMember = membership(504L, room, user(312L, Role.USER));
        RoomMemberIdentifier identifier = identifier(roomMember);
        ModerationQueue queue = ModerationQueue.builder()
                .id(701L)
                .room(room)
                .roomMember(roomMember)
                .entityType("ROOM_MEMBER")
                .entityId(roomMember.getId())
                .reasonCode("SUPPORT_TICKET")
                .status(ModerationQueueStatus.IN_REVIEW)
                .assignedAdmin(admin)
                .build();
        RevealIdentifierRequest request = revealRequest("Admin moderation review", "moderation", queue.getId());

        when(roomRepository.findById(room.getId())).thenReturn(Optional.of(room));
        when(roomMemberRepository.findByIdAndRoomAndDeletedAtIsNull(roomMember.getId(), room))
                .thenReturn(Optional.of(roomMember));
        when(paymentTransactionRepository.existsByRoomMember_IdAndStatus(roomMember.getId(), PaymentTransactionStatus.SUCCESS))
                .thenReturn(true);
        when(moderationQueueRepository.findByIdAndRoomMemberAndStatusIn(eq(queue.getId()), eq(roomMember), anyList()))
                .thenReturn(Optional.of(queue));
        when(roomMemberIdentifierRepository.findByRoomMember(roomMember)).thenReturn(Optional.of(identifier));
        when(fieldEncryptionService.decrypt(identifier.getIdentifierEncrypted())).thenReturn("+77021234567");

        RevealedIdentifierDto result = roomMemberService.revealIdentifierForStaff(
                room.getId(),
                roomMember.getId(),
                admin,
                request,
                httpRequest()
        );

        assertEquals("+77021234567", result.getIdentifierValue());
        verify(roomEventLogRepository).save(argThat(log ->
                "ADMIN".equals(log.getActorRole())
                        && "MODERATION".equals(log.getNewState().get("contextType").asText())
        ));
    }

    @Test
    void revealIdentifierForStaff_allowsDisputeContextForAssignedActor() {
        User admin = user(313L, Role.ADMIN);
        Room room = telecomRoom(405L, user(314L, Role.USER));
        RoomMember roomMember = membership(505L, room, user(315L, Role.USER));
        RoomMemberIdentifier identifier = identifier(roomMember);
        Dispute dispute = Dispute.builder()
                .id(801L)
                .room(room)
                .roomMember(roomMember)
                .openedByUser(roomMember.getUser())
                .assignedAdmin(admin)
                .reasonCode("ACCESS_ISSUE")
                .status(DisputeStatus.UNDER_REVIEW)
                .build();
        RevealIdentifierRequest request = revealRequest("Dispute investigation", "dispute", dispute.getId());

        when(roomRepository.findById(room.getId())).thenReturn(Optional.of(room));
        when(roomMemberRepository.findByIdAndRoomAndDeletedAtIsNull(roomMember.getId(), room))
                .thenReturn(Optional.of(roomMember));
        when(paymentTransactionRepository.existsByRoomMember_IdAndStatus(roomMember.getId(), PaymentTransactionStatus.SUCCESS))
                .thenReturn(true);
        when(disputeRepository.findByIdAndRoomMemberAndStatusIn(eq(dispute.getId()), eq(roomMember), anyList()))
                .thenReturn(Optional.of(dispute));
        when(roomMemberIdentifierRepository.findByRoomMember(roomMember)).thenReturn(Optional.of(identifier));
        when(fieldEncryptionService.decrypt(identifier.getIdentifierEncrypted())).thenReturn("+77031234567");

        RevealedIdentifierDto result = roomMemberService.revealIdentifierForStaff(
                room.getId(),
                roomMember.getId(),
                admin,
                request,
                httpRequest()
        );

        assertEquals("+77031234567", result.getIdentifierValue());
        verify(roomEventLogRepository).save(argThat(log ->
                "DISPUTE".equals(log.getNewState().get("contextType").asText())
                        && log.getNewState().get("audit").get("contextId").asLong() == dispute.getId()
        ));
    }

    private void mockConfirmMemberAccessLookup(Room room, User member, RoomMember roomMember) {
        when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(roomMemberRepository.findByRoomAndUserAndDeletedAtIsNull(room, member))
                .thenReturn(Optional.of(roomMember));
    }

    private Room room(Long roomId) {
        return Room.builder()
                .id(roomId)
                .owner(user(1L, Role.USER))
                .roomType(RoomType.DIGITAL)
                .verificationMode(VerificationMode.AUTO)
                .status(RoomStatus.OPEN)
                .title("Test room")
                .maxMembers(2)
                .currency("KZT")
                .startDate(LocalDateTime.now().plusDays(1))
                .build();
    }

    private Room telecomRoom(Long roomId, User owner) {
        return Room.builder()
                .id(roomId)
                .owner(owner)
                .roomType(RoomType.TELECOM)
                .verificationMode(VerificationMode.AUTO)
                .status(RoomStatus.OPEN)
                .title("Telecom room")
                .maxMembers(2)
                .currency("KZT")
                .startDate(LocalDateTime.now().plusDays(1))
                .build();
    }

    private RoomMember pendingMembership(Long membershipId, Room room, User member) {
        return RoomMember.builder()
                .id(membershipId)
                .room(room)
                .user(member)
                .status(MemberStatus.PENDING)
                .requiresAdminReview(false)
                .ownerAccessConfirmedAt(LocalDateTime.now().minusMinutes(10))
                .build();
    }

    private RoomMember membership(Long membershipId, Room room, User member) {
        return RoomMember.builder()
                .id(membershipId)
                .room(room)
                .user(member)
                .status(MemberStatus.ACTIVE)
                .requiresAdminReview(false)
                .ownerAccessConfirmedAt(LocalDateTime.now().minusMinutes(10))
                .memberConfirmedAt(LocalDateTime.now().minusMinutes(5))
                .activatedAt(LocalDateTime.now().minusMinutes(3))
                .build();
    }

    private RoomMemberIdentifier identifier(RoomMember roomMember) {
        return RoomMemberIdentifier.builder()
                .id(roomMember.getId() + 1000)
                .roomMember(roomMember)
                .identifierType(kz.hrms.splitupauth.entity.IdentifierType.PHONE)
                .identifierEncrypted("encrypted")
                .identifierMasked("+7700*****67")
                .isValidFormat(true)
                .build();
    }

    private RevealIdentifierRequest revealRequest(String reason, String contextType, Long contextId) {
        RevealIdentifierRequest request = new RevealIdentifierRequest();
        request.setReason(reason);
        request.setContextType(contextType);
        request.setContextId(contextId);
        return request;
    }

    private HttpServletRequest httpRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        lenient().when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        lenient().when(request.getHeader("User-Agent")).thenReturn("JUnit");
        return request;
    }

    private User user(Long userId, Role role) {
        return User.builder()
                .id(userId)
                .email("user" + userId + "@example.com")
                .password("secret")
                .role(role)
                .displayName("User " + userId)
                .status(UserStatus.ACTIVE)
                .build();
    }
}
