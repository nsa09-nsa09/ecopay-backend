package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.dto.ConfirmOwnerAccessRequest;
import kz.hrms.splitupauth.dto.CreatePaymentIntentRequest;
import kz.hrms.splitupauth.dto.CreateRoomRequest;
import kz.hrms.splitupauth.dto.JoinRoomRequest;
import kz.hrms.splitupauth.dto.PaymentIntentResponse;
import kz.hrms.splitupauth.dto.RegisterRequest;
import kz.hrms.splitupauth.dto.RoomMemberDto;
import kz.hrms.splitupauth.dto.RoomResponse;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.RoomType;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full business chain against a real Postgres (Testcontainers): register →
 * verify phone → create room → join → pay (mock) → owner grants → member
 * confirms → ACTIVE, plus persistence-level invariants (audit log written,
 * room_event_log append-only enforced by the V8 trigger).
 */
class ChainIntegrationTest extends AbstractIntegrationTest {

    @Autowired AuthService authService;
    @Autowired PhoneVerificationService phoneVerificationService;
    @Autowired RoomService roomService;
    @Autowired RoomMemberService roomMemberService;
    @Autowired PaymentService paymentService;
    @Autowired UserRepository userRepository;
    @Autowired RoomRepository roomRepository;
    @Autowired RoomMemberRepository roomMemberRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private User registerVerified(String name) {
        int n = SEQ.incrementAndGet();
        RegisterRequest req = new RegisterRequest();
        req.setEmail("it_" + n + "_" + System.nanoTime() + "@test.kz");
        req.setPassword("Test1234");
        req.setDisplayName(name);

        authService.register(req);
        User user = userRepository.findByEmail(req.getEmail()).orElseThrow();
        // Phone is now collected at room-creation time. Attach + verify it via
        // dev-bypass here so the subsequent createRoom path still passes the
        // phoneVerifiedAt gate.
        String phone = "+77" + String.format("%09d", (System.nanoTime() % 1_000_000_000L));
        phoneVerificationService.requestCode(user, phone);
        phoneVerificationService.verifyCode(user, phone, "000000");
        return userRepository.findByEmail(req.getEmail()).orElseThrow();
    }

    /** Owners must have an active default payout card before they can create a room. */
    private void givePayoutCard(User owner) {
        jdbcTemplate.update(
                "INSERT INTO payout_methods (user_id, provider_name, provider_card_token, pan_mask, "
                        + "is_default, status, created_at) VALUES (?, 'freedompay', ?, '4242', TRUE, "
                        + "'ACTIVE', CURRENT_TIMESTAMP)",
                owner.getId(), "tok_test_" + owner.getId());
    }

    @Test
    void fullChain_reachesActiveMembership_andWritesAppendOnlyAudit() {
        User owner = registerVerified("IT Owner");
        givePayoutCard(owner);
        User member = registerVerified("IT Member");

        // Create a DIGITAL room on the seeded Netflix service (V7).
        CreateRoomRequest create = new CreateRoomRequest();
        create.setServiceId(2L);
        create.setTariffPlanId(2L);
        create.setCategoryId(1L);
        create.setRoomType(RoomType.DIGITAL);
        create.setTitle("IT Netflix");
        // Seats/price/currency/period now come from the seeded tariff plan (V10): 7290.00 / 4 monthly.
        create.setStartDate(LocalDateTime.now().plusMonths(2));
        RoomResponse room = roomService.createRoom(owner, create);
        assertEquals(RoomStatus.OPEN, room.getStatus());

        // Join
        JoinRoomRequest join = new JoinRoomRequest();
        join.setConsentAccepted(true);
        RoomMemberDto membership = roomMemberService.joinRoom(room.getId(), member, join);
        assertEquals(MemberStatus.APPLIED, membership.getStatus());

        // Pay via mock gateway → synchronous SUCCESS, membership → PENDING
        CreatePaymentIntentRequest pay = new CreatePaymentIntentRequest();
        pay.setIdempotencyKey("it-" + membership.getId());
        PaymentIntentResponse intent = paymentService.createPaymentIntent(membership.getId(), member, pay);
        assertEquals(PaymentIntentStatus.SUCCESS, intent.getStatus());
        // Share = 7290.00 / 4 = 1822.50; ECOpay commission for that tier (<= 4000) = 500.00;
        // the member is charged share + commission = 2322.50.
        assertEquals(0, new BigDecimal("1822.50").compareTo(intent.getShareAmount()));
        assertEquals(0, new BigDecimal("500.00").compareTo(intent.getCommissionAmount()));
        assertEquals(0, new BigDecimal("2322.50").compareTo(intent.getAmount()));
        assertEquals(MemberStatus.PENDING,
                roomMemberRepository.findById(membership.getId()).orElseThrow().getStatus());

        // Owner grants → member confirms → ACTIVE, and room auto-activates
        ConfirmOwnerAccessRequest grant = new ConfirmOwnerAccessRequest();
        grant.setAccessMethod("invite_link");
        roomMemberService.confirmOwnerAccess(room.getId(), membership.getId(), owner, grant);
        roomMemberService.confirmMemberAccess(room.getId(), member);

        RoomMember finalMember = roomMemberRepository.findById(membership.getId()).orElseThrow();
        assertEquals(MemberStatus.ACTIVE, finalMember.getStatus());
        Room finalRoom = roomRepository.findById(room.getId()).orElseThrow();
        assertEquals(RoomStatus.ACTIVE, finalRoom.getStatus());

        // Audit trail written
        Integer events = jdbcTemplate.queryForObject(
                "select count(*) from room_event_log where room_id = ?", Integer.class, room.getId());
        assertTrue(events != null && events >= 5, "expected room_event_log entries, got " + events);

        // Append-only enforced by the V8 trigger
        assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("update room_event_log set event_type = 'x' where room_id = ?", room.getId()));
    }

    @Test
    void unverifiedPhone_cannotCreateRoom() {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("it_unv_" + System.nanoTime() + "@test.kz");
        req.setPassword("Test1234");
        req.setDisplayName("Unverified");
        // Phone is no longer collected at registration; this account stays
        // phone-less, which keeps phoneVerifiedAt = null and trips the
        // createRoom gate below.
        authService.register(req);
        User user = userRepository.findByEmail(req.getEmail()).orElseThrow();

        CreateRoomRequest create = new CreateRoomRequest();
        create.setServiceId(2L);
        create.setTariffPlanId(2L);
        create.setRoomType(RoomType.DIGITAL);
        create.setTitle("blocked");
        create.setStartDate(LocalDateTime.now().plusMonths(2));

        assertThrows(RuntimeException.class, () -> roomService.createRoom(user, create));
    }
}
