package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.dto.CancelRoomRequest;
import kz.hrms.splitupauth.dto.ConfirmOwnerAccessRequest;
import kz.hrms.splitupauth.dto.CreatePaymentIntentRequest;
import kz.hrms.splitupauth.dto.CreateRoomComplaintRequest;
import kz.hrms.splitupauth.dto.CreateRoomRequest;
import kz.hrms.splitupauth.dto.DisputeDecisionRequest;
import kz.hrms.splitupauth.dto.DisputeResponse;
import kz.hrms.splitupauth.dto.JoinRoomRequest;
import kz.hrms.splitupauth.dto.PaymentIntentResponse;
import kz.hrms.splitupauth.dto.RegisterRequest;
import kz.hrms.splitupauth.dto.RoomMemberDto;
import kz.hrms.splitupauth.dto.RoomResponse;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import kz.hrms.splitupauth.entity.PayoutStatus;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.RoomType;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.payment.gateway.GatewayWebhookEvent;
import kz.hrms.splitupauth.payment.gateway.MockPaymentGateway;
import kz.hrms.splitupauth.repository.PaymentIntentRepository;
import kz.hrms.splitupauth.repository.PayoutRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

/**
 * C5: six-step finance chain against a real Postgres (Testcontainers) + mock gateway.
 *
 * <ol>
 *   <li>join → intent → webhook SUCCESS → PENDING
 *   <li>idempotent webhook (duplicate → still one SUCCESS tx)
 *   <li>owner grants access → member confirms → ACTIVE + payout created
 *   <li>dispute FAVOR_MEMBER → refund issued + payout blocked
 *   <li>second member → owner cancels room → auto-refund + ROOM_CANCELLED
 *   <li>double confirm-success on a single intent → exactly one SUCCESS transaction
 * </ol>
 */
@Import(FinanceChainIntegrationTest.TestClockConfig.class)
@TestPropertySource(properties = "app.payout.batch-coalesce-hours=0")
class FinanceChainIntegrationTest extends AbstractIntegrationTest {

  private static final Instant BASE = Instant.parse("2026-02-01T00:00:00Z");

  @Autowired AuthService authService;
  @Autowired PhoneVerificationService phoneVerificationService;
  @Autowired RoomService roomService;
  @Autowired RoomMemberService roomMemberService;
  @Autowired PaymentService paymentService;
  @Autowired PayoutService payoutService;
  @Autowired DisputeService disputeService;
  @Autowired UserRepository userRepository;
  @Autowired RoomRepository roomRepository;
  @Autowired RoomMemberRepository roomMemberRepository;
  @Autowired PaymentIntentRepository paymentIntentRepository;
  @Autowired PayoutRepository payoutRepository;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired EntityManager entityManager;
  @Autowired MockPaymentGateway mockGateway;
  @Autowired TestMutableClock testClock;

  private static final AtomicInteger SEQ = new AtomicInteger();

  @BeforeEach
  void setUp() {
    testClock.set(BASE);
    mockGateway.resetCounters();
    jdbcTemplate.update("UPDATE room_settings SET minimum_room_members = 4 WHERE id = 1");
  }

  @Test
  void fullFinanceChain_sixSteps() {
    User owner = register("FC Owner");
    givePayoutCard(owner);
    User guest1 = register("FC Guest1");
    User guest2 = register("FC Guest2");
    User admin = register("FC Admin");
    jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", admin.getId());
    admin = userRepository.findById(admin.getId()).orElseThrow();

    // ── Step 1: join → intent → webhook SUCCESS → PENDING ──

    RoomResponse room = createRoom(owner, "FC Chain Room");
    RoomMemberDto m1 = join(room.getId(), guest1, "fc-guest1@test.kz");
    assertEquals(MemberStatus.APPLIED, m1.getStatus());

    PaymentIntentResponse intent1 = pay(m1, guest1, "fc-key-" + m1.getId());
    assertEquals(PaymentIntentStatus.SUCCESS, intent1.getStatus());
    assertEquals(
        MemberStatus.PENDING,
        roomMemberRepository.findById(m1.getId()).orElseThrow().getStatus());
    assertEquals(1L, countSuccessCharges(intent1.getId()));

    // ── Step 2: idempotent webhook ──

    var intentEntity = paymentIntentRepository.findById(intent1.getId()).orElseThrow();
    GatewayWebhookEvent dupEvent =
        GatewayWebhookEvent.builder()
            .intentId(intentEntity.getId())
            .resultStatus("SUCCESS")
            .amount(intentEntity.getAmount())
            .currency("KZT")
            .externalPaymentId(intentEntity.getExternalPaymentId())
            .providerRequestId("dup-fc-" + intentEntity.getId())
            .build();
    paymentService.applyWebhookEvent(dupEvent);
    paymentService.applyWebhookEvent(dupEvent);

    assertEquals(1L, countSuccessCharges(intent1.getId()),
        "duplicate webhook must not create extra SUCCESS transactions");
    assertEquals(1L, countPayouts(intent1.getId()),
        "duplicate webhook must not create extra payouts");

    // ── Step 3: owner grants → member confirms → ACTIVE + payout ──

    ConfirmOwnerAccessRequest grant = new ConfirmOwnerAccessRequest();
    grant.setAccessMethod("invite_link");
    roomMemberService.confirmOwnerAccess(room.getId(), m1.getId(), owner, grant);
    roomMemberService.confirmMemberAccess(room.getId(), guest1);

    assertEquals(
        MemberStatus.ACTIVE,
        roomMemberRepository.findById(m1.getId()).orElseThrow().getStatus());
    assertEquals(
        RoomStatus.ACTIVE,
        roomRepository.findById(room.getId()).orElseThrow().getStatus());

    var payout1 = payoutRepository.findByTriggeringPaymentIntent(intentEntity).orElseThrow();
    assertNotNull(payout1.getId());
    assertTrue(payout1.getAmount().compareTo(BigDecimal.ZERO) > 0);

    // ── Step 4: dispute FAVOR_MEMBER → refund + payout frozen ──

    CreateRoomComplaintRequest complaint = new CreateRoomComplaintRequest();
    complaint.setReasonCode("PHONE_NOT_CONNECTED");
    complaint.setDescription("Owner never gave access to the service, total scam scenario");
    DisputeResponse dispute = disputeService.openMemberComplaint(room.getId(), guest1, complaint);

    entityManager.clear();
    assertEquals(PayoutStatus.FROZEN,
        payoutRepository.findById(payout1.getId()).orElseThrow().getStatus(),
        "payout must be frozen when dispute is opened");

    setAuth(admin);
    try {
      DisputeDecisionRequest decision = new DisputeDecisionRequest();
      decision.setStatus("RESOLVED");
      decision.setDecision("FAVOR_MEMBER");
      decision.setComment("Owner confirmed service was never provided");
      MockHttpServletRequest http = new MockHttpServletRequest();
      http.addHeader("User-Agent", "fc-test");
      disputeService.decide(dispute.getId(), admin, decision, http);
    } finally {
      SecurityContextHolder.clearContext();
    }

    entityManager.clear();
    assertTrue(countRefunds(intent1.getId()) > 0,
        "dispute FAVOR_MEMBER must trigger a refund");

    // ── Step 5: second member + room cancel → auto-refund + ROOM_CANCELLED ──

    RoomMemberDto m2 = join(room.getId(), guest2, "fc-guest2@test.kz");
    PaymentIntentResponse intent2 = pay(m2, guest2, "fc-key-" + m2.getId());
    assertEquals(PaymentIntentStatus.SUCCESS, intent2.getStatus());

    ConfirmOwnerAccessRequest grant2 = new ConfirmOwnerAccessRequest();
    grant2.setAccessMethod("invite_link");
    roomMemberService.confirmOwnerAccess(room.getId(), m2.getId(), owner, grant2);
    roomMemberService.confirmMemberAccess(room.getId(), guest2);
    assertEquals(
        MemberStatus.ACTIVE,
        roomMemberRepository.findById(m2.getId()).orElseThrow().getStatus());

    CancelRoomRequest cancelReq = new CancelRoomRequest();
    cancelReq.setReason("Owner decided to stop the room completely");
    roomService.cancelRoom(room.getId(), owner, cancelReq);

    entityManager.clear();
    assertEquals(RoomStatus.CANCELLED,
        roomRepository.findById(room.getId()).orElseThrow().getStatus());
    assertEquals(MemberStatus.ROOM_CANCELLED,
        roomMemberRepository.findById(m2.getId()).orElseThrow().getStatus());
    assertTrue(countRefunds(intent2.getId()) > 0,
        "room cancellation must auto-refund paid members");

    // ── Step 6: double confirm-success → exactly one SUCCESS tx ──

    User owner2 = register("FC Owner2");
    givePayoutCard(owner2);
    User guest3 = register("FC Guest3");
    RoomResponse room2 = createRoom(owner2, "FC Double Room");
    RoomMemberDto m3 = join(room2.getId(), guest3, "fc-guest3@test.kz");
    PaymentIntentResponse intent3 = pay(m3, guest3, "fc-double-" + m3.getId());
    assertEquals(PaymentIntentStatus.SUCCESS, intent3.getStatus());

    var intentEntity3 = paymentIntentRepository.findById(intent3.getId()).orElseThrow();
    GatewayWebhookEvent confirmEvent =
        GatewayWebhookEvent.builder()
            .intentId(intentEntity3.getId())
            .resultStatus("SUCCESS")
            .amount(intentEntity3.getAmount())
            .currency("KZT")
            .externalPaymentId(intentEntity3.getExternalPaymentId())
            .providerRequestId("double-confirm-" + intentEntity3.getId())
            .build();
    paymentService.applyWebhookEvent(confirmEvent);
    paymentService.applyWebhookEvent(confirmEvent);
    paymentService.applyWebhookEvent(confirmEvent);

    assertEquals(1L, countSuccessCharges(intent3.getId()),
        "triple confirm must produce exactly one SUCCESS charge");
    assertEquals(1L, countPayouts(intent3.getId()),
        "triple confirm must produce exactly one payout");
  }

  // ── helpers ──

  private User register(String name) {
    int n = SEQ.incrementAndGet();
    RegisterRequest req = new RegisterRequest();
    req.setEmail("fc_" + n + "_" + System.nanoTime() + "@test.kz");
    req.setPassword("Test1234");
    req.setDisplayName(name);
    authService.register(req, MailLocale.RU, null);
    User user = userRepository.findByEmail(req.getEmail()).orElseThrow();
    String phone = "+77" + String.format("%09d", (System.nanoTime() % 1_000_000_000L));
    phoneVerificationService.requestCode(user, phone, null);
    phoneVerificationService.verifyCode(user, phone, "000000");
    return userRepository.findByEmail(req.getEmail()).orElseThrow();
  }

  private void givePayoutCard(User owner) {
    Long existing =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payout_methods WHERE user_id = ? AND is_default = TRUE "
                + "AND status = 'ACTIVE'",
            Long.class,
            owner.getId());
    if (existing != null && existing > 0) return;
    jdbcTemplate.update(
        "INSERT INTO payout_methods (user_id, provider_name, provider_card_token, pan_mask, "
            + "is_default, status, token_source, created_at) VALUES (?, 'mock', ?, '6666', TRUE, "
            + "'ACTIVE', 'PAYOUT_CARD_TOKEN', CURRENT_TIMESTAMP)",
        owner.getId(),
        "tok_fc_" + owner.getId());
  }

  private RoomResponse createRoom(User host, String title) {
    givePayoutCard(host);
    CreateRoomRequest req = new CreateRoomRequest();
    req.setServiceId(2L);
    req.setTariffPlanId(2L);
    req.setCategoryId(1L);
    req.setRoomType(RoomType.DIGITAL);
    req.setTitle(title);
    req.setStartDate(LocalDateTime.now().plusMonths(2));
    return roomService.createRoom(host, req);
  }

  private RoomMemberDto join(Long roomId, User guest, String email) {
    JoinRoomRequest req = new JoinRoomRequest();
    req.setConsentAccepted(true);
    req.setIdentifierValue(email);
    return roomMemberService.joinRoom(roomId, guest, req);
  }

  private PaymentIntentResponse pay(RoomMemberDto membership, User guest, String key) {
    CreatePaymentIntentRequest req = new CreatePaymentIntentRequest();
    req.setIdempotencyKey(key);
    return paymentService.createPaymentIntent(membership.getId(), guest, req);
  }

  private Long countSuccessCharges(Long intentId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM payment_transactions "
            + "WHERE payment_intent_id = ? AND type = 'CHARGE' AND status = 'SUCCESS'",
        Long.class,
        intentId);
  }

  private Long countPayouts(Long intentId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM payouts WHERE triggering_payment_intent_id = ?",
        Long.class,
        intentId);
  }

  private Long countRefunds(Long intentId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM refund_transactions rt "
            + "JOIN payment_transactions pt ON pt.id = rt.payment_transaction_id "
            + "WHERE pt.payment_intent_id = ?",
        Long.class,
        intentId);
  }

  private void setAuth(User admin) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                admin, null, List.of(new SimpleGrantedAuthority("ADMIN"))));
  }

  @TestConfiguration
  static class TestClockConfig {
    @Bean
    @Primary
    TestMutableClock testMutableClock() {
      return new TestMutableClock(BASE, ZoneOffset.UTC);
    }
  }

  static final class TestMutableClock extends Clock {
    private final AtomicReference<Instant> instant;
    private final ZoneId zone;

    TestMutableClock(Instant instant, ZoneId zone) {
      this.instant = new AtomicReference<>(instant);
      this.zone = zone;
    }

    void set(Instant instant) {
      this.instant.set(instant);
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return new TestMutableClock(instant.get(), zone);
    }

    @Override
    public Instant instant() {
      return instant.get();
    }
  }
}
