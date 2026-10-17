package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.dto.CreatePaymentIntentRequest;
import kz.hrms.splitupauth.dto.CreateRoomRequest;
import kz.hrms.splitupauth.dto.JoinRoomRequest;
import kz.hrms.splitupauth.dto.PaymentIntentResponse;
import kz.hrms.splitupauth.dto.RegisterRequest;
import kz.hrms.splitupauth.dto.RenewalIntentRequest;
import kz.hrms.splitupauth.dto.RoomMemberDto;
import kz.hrms.splitupauth.dto.RoomResponse;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentIntentPurpose;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.RoomType;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceConflictException;
import kz.hrms.splitupauth.payment.gateway.MockPaymentGateway;
import kz.hrms.splitupauth.repository.PaymentIntentRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * B2 end-to-end: manual renewal of a membership for the next billing period through the mock
 * gateway, on a real Postgres. Proves the window gate, idempotency/dedup, the single period advance
 * + owner payout on capture, recurring deferring to a renewal-paid period, YEARLY cadence, and the
 * daily reminder.
 */
@Import(RenewalPaymentIntegrationTest.ClockTestConfig.class)
class RenewalPaymentIntegrationTest extends AbstractIntegrationTest {

  private static final Instant BASE_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

  @Autowired AuthService authService;
  @Autowired PhoneVerificationService phoneVerificationService;
  @Autowired RoomService roomService;
  @Autowired RoomMemberService roomMemberService;
  @Autowired PaymentService paymentService;
  @Autowired RenewalPaymentService renewalPaymentService;
  @Autowired RecurringChargeService recurringChargeService;
  @Autowired kz.hrms.splitupauth.scheduler.RenewalReminderScheduler renewalReminderScheduler;
  @Autowired UserRepository userRepository;
  @Autowired RoomMemberRepository roomMemberRepository;
  @Autowired PaymentIntentRepository paymentIntentRepository;
  @Autowired JdbcTemplate jdbc;
  @Autowired MutableClock clock;
  @Autowired MockPaymentGateway mockGateway;

  private static final AtomicInteger SEQ = new AtomicInteger();

  @BeforeEach
  void reset() {
    clock.set(BASE_INSTANT);
    mockGateway.resetCounters();
    jdbc.update("UPDATE room_settings SET minimum_room_members = 4 WHERE id = 1");
  }

  // ----------------------------------------------------------------- helpers

  private User registerVerified(String name) {
    int n = SEQ.incrementAndGet();
    RegisterRequest req = new RegisterRequest();
    req.setEmail("rnw_" + n + "_" + System.nanoTime() + "@test.kz");
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
    jdbc.update(
        "INSERT INTO payout_methods (user_id, provider_name, provider_card_token, pan_mask, "
            + "is_default, status, token_source, created_at) VALUES (?, 'mock', ?, '6666', TRUE, "
            + "'ACTIVE', 'PAYOUT_CARD_TOKEN', CURRENT_TIMESTAMP)",
        owner.getId(),
        "tok_rnw_" + owner.getId());
  }

  /**
   * Member who has paid the first period (schedule initialized) and is now ACTIVE in an ACTIVE
   * room.
   */
  private record Ctx(User guest, Long memberId, Long roomId) {}

  private Ctx activeMemberWithSchedule(String tag) {
    User host = registerVerified(tag + " Host");
    givePayoutCard(host);
    User guest = registerVerified(tag + " Guest");

    CreateRoomRequest create = new CreateRoomRequest();
    create.setServiceId(2L);
    create.setTariffPlanId(2L);
    create.setCategoryId(1L);
    create.setRoomType(RoomType.DIGITAL);
    create.setTitle(tag + " Room");
    create.setStartDate(LocalDateTime.now().plusMonths(2));
    RoomResponse room = roomService.createRoom(host, create);

    JoinRoomRequest join = new JoinRoomRequest();
    join.setConsentAccepted(true);
    join.setIdentifierValue(tag + "@test.kz");
    RoomMemberDto member = roomMemberService.joinRoom(room.getId(), guest, join);

    CreatePaymentIntentRequest pay = new CreatePaymentIntentRequest();
    pay.setIdempotencyKey("init-" + member.getId());
    PaymentIntentResponse initial = paymentService.createPaymentIntent(member.getId(), guest, pay);
    assertEquals(PaymentIntentStatus.SUCCESS, initial.getStatus());

    // Force ACTIVE member + ACTIVE room so the renewal flow's state checks pass deterministically.
    jdbc.update("UPDATE room_members SET status = 'ACTIVE' WHERE id = ?", member.getId());
    jdbc.update("UPDATE rooms SET status = 'ACTIVE' WHERE id = ?", room.getId());

    RoomMember reloaded = roomMemberRepository.findById(member.getId()).orElseThrow();
    assertNotNull(
        reloaded.getNextBillingAt(), "first payment must initialize the billing schedule");
    assertEquals(
        LocalDateTime.ofInstant(BASE_INSTANT, ZoneOffset.UTC).plusMonths(1),
        reloaded.getNextBillingAt());
    return new Ctx(guest, member.getId(), room.getId());
  }

  private LocalDateTime nextBillingAt(Long memberId) {
    return roomMemberRepository.findById(memberId).orElseThrow().getNextBillingAt();
  }

  private RenewalIntentRequest renewalReq(String key) {
    RenewalIntentRequest r = new RenewalIntentRequest();
    r.setIdempotencyKey(key);
    return r;
  }

  // ----------------------------------------------------------------- tests

  @Test
  void renewalOutsideWindowIsRejected() {
    Ctx ctx = activeMemberWithSchedule("OutOfWindow");
    // now = BASE, nextBillingAt = BASE + 1 month, window opens 5 days before -> not open yet.
    InvalidRequestException ex =
        assertThrows(
            InvalidRequestException.class,
            () ->
                renewalPaymentService.createRenewalIntent(
                    ctx.memberId(), ctx.guest(), renewalReq("rnw-oow-" + ctx.memberId())));
    assertEquals("RENEWAL_NOT_OPEN", ex.getCode());
  }

  @Test
  void renewalInsideWindowReturnsRedirectIntent() {
    Ctx ctx = activeMemberWithSchedule("InWindow");
    mockGateway.setAsyncCapture(true); // hosted page: redirect, not captured yet
    openWindow(ctx.memberId());

    PaymentIntentResponse resp =
        renewalPaymentService.createRenewalIntent(
            ctx.memberId(), ctx.guest(), renewalReq("rnw-win-" + ctx.memberId()));

    assertEquals(PaymentIntentStatus.PENDING, resp.getStatus());
    assertNotNull(resp.getPaymentUrl(), "renewal must hand back a hosted payment URL");
  }

  @Test
  void capturedRenewalAdvancesPeriodOnceAndCreatesOwnerPayout() {
    Ctx ctx = activeMemberWithSchedule("Captured");
    mockGateway.setAsyncCapture(true);
    openWindow(ctx.memberId());
    LocalDateTime period = nextBillingAt(ctx.memberId());

    PaymentIntentResponse resp =
        renewalPaymentService.createRenewalIntent(
            ctx.memberId(), ctx.guest(), renewalReq("rnw-cap-" + ctx.memberId()));
    PaymentIntent intent = paymentIntentRepository.findById(resp.getId()).orElseThrow();
    assertEquals(PaymentIntentPurpose.RENEWAL, intent.getPurpose());

    paymentService.finalizeSuccessfulPayment(
        intent.getId(),
        intent.getExternalPaymentId(),
        "ok",
        null,
        null,
        null,
        null,
        "TEST_RENEWAL");

    assertEquals(
        period.plusMonths(1), nextBillingAt(ctx.memberId()), "period advances exactly once");
    Long payouts =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM payouts WHERE triggering_payment_intent_id = ?",
            Long.class,
            intent.getId());
    assertEquals(1L, payouts, "a captured renewal creates the owner payout");

    // Duplicate finalize (webhook retry) must not advance the period a second time.
    paymentService.finalizeSuccessfulPayment(
        intent.getId(),
        intent.getExternalPaymentId(),
        "ok",
        null,
        null,
        null,
        null,
        "TEST_RENEWAL");
    assertEquals(period.plusMonths(1), nextBillingAt(ctx.memberId()), "no second advance");
  }

  @Test
  void secondRenewalForAnAlreadyPaidPeriodConflicts() {
    Ctx ctx = activeMemberWithSchedule("AlreadyPaid");
    openWindow(ctx.memberId());
    LocalDateTime period = nextBillingAt(ctx.memberId());
    // Simulate a captured renewal that already covers the current period.
    RoomMember member = roomMemberRepository.findById(ctx.memberId()).orElseThrow();
    paymentIntentRepository.save(
        PaymentIntent.builder()
            .idempotencyKey("captured-" + ctx.memberId())
            .roomMember(member)
            .user(member.getUser())
            .amount(new java.math.BigDecimal("2322.50"))
            .commissionAmount(new java.math.BigDecimal("500.00"))
            .status(PaymentIntentStatus.SUCCESS)
            .providerName("mock")
            .purpose(PaymentIntentPurpose.RENEWAL)
            .billingPeriodStart(period)
            .build());

    ResourceConflictException ex =
        assertThrows(
            ResourceConflictException.class,
            () ->
                renewalPaymentService.createRenewalIntent(
                    ctx.memberId(), ctx.guest(), renewalReq("rnw-dup-" + ctx.memberId())));
    assertEquals("RENEWAL_ALREADY_PAID", ex.getCode());
  }

  @Test
  void sameIdempotencyKeyReturnsSameIntent() {
    Ctx ctx = activeMemberWithSchedule("Idem");
    mockGateway.setAsyncCapture(true);
    openWindow(ctx.memberId());
    String key = "rnw-idem-" + ctx.memberId();

    PaymentIntentResponse a =
        renewalPaymentService.createRenewalIntent(ctx.memberId(), ctx.guest(), renewalReq(key));
    PaymentIntentResponse b =
        renewalPaymentService.createRenewalIntent(ctx.memberId(), ctx.guest(), renewalReq(key));
    assertEquals(a.getId(), b.getId());
  }

  @Test
  void recurringSkipsAPeriodAlreadyPaidByRenewal() {
    Ctx ctx = activeMemberWithSchedule("RecurringSkip");
    openWindow(ctx.memberId());
    LocalDateTime period = nextBillingAt(ctx.memberId());
    RoomMember member = roomMemberRepository.findById(ctx.memberId()).orElseThrow();
    paymentIntentRepository.save(
        PaymentIntent.builder()
            .idempotencyKey("rnw-paid-" + ctx.memberId())
            .roomMember(member)
            .user(member.getUser())
            .amount(new java.math.BigDecimal("2322.50"))
            .commissionAmount(new java.math.BigDecimal("500.00"))
            .status(PaymentIntentStatus.SUCCESS)
            .providerName("mock")
            .purpose(PaymentIntentPurpose.RENEWAL)
            .billingPeriodStart(period)
            .build());

    boolean prior =
        (boolean) ReflectionTestUtils.getField(recurringChargeService, "recurringEnabled");
    ReflectionTestUtils.setField(recurringChargeService, "recurringEnabled", true);
    try {
      recurringChargeService.tryAutoCharge(ctx.memberId());
    } finally {
      ReflectionTestUtils.setField(recurringChargeService, "recurringEnabled", prior);
    }

    // No recurring charge was attempted for the renewal-paid period, and the period advanced.
    Long recurringIntents =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM payment_intents WHERE room_member_id = ? AND purpose = 'RECURRING'",
            Long.class,
            ctx.memberId());
    assertEquals(0L, recurringIntents);
    assertEquals(period.plusMonths(1), nextBillingAt(ctx.memberId()));
  }

  @Test
  void yearlyRenewalAdvancesByAYear() {
    Ctx ctx = activeMemberWithSchedule("Yearly");
    // Switch the plan to yearly and re-anchor the schedule a year out from the base.
    jdbc.update("UPDATE rooms SET period_type = 'YEARLY' WHERE id = ?", ctx.roomId());
    LocalDateTime period = LocalDateTime.ofInstant(BASE_INSTANT, ZoneOffset.UTC).plusYears(1);
    jdbc.update(
        "UPDATE room_members SET next_billing_at = ?, billing_period_start = ? WHERE id = ?",
        java.sql.Timestamp.valueOf(period),
        java.sql.Timestamp.valueOf(period.minusYears(1)),
        ctx.memberId());
    clock.set(period.minusDays(2).toInstant(ZoneOffset.UTC)); // inside the window

    mockGateway.setAsyncCapture(true);
    PaymentIntentResponse resp =
        renewalPaymentService.createRenewalIntent(
            ctx.memberId(), ctx.guest(), renewalReq("rnw-year-" + ctx.memberId()));
    PaymentIntent intent = paymentIntentRepository.findById(resp.getId()).orElseThrow();
    paymentService.finalizeSuccessfulPayment(
        intent.getId(),
        intent.getExternalPaymentId(),
        "ok",
        null,
        null,
        null,
        null,
        "TEST_RENEWAL");

    assertEquals(period.plusYears(1), nextBillingAt(ctx.memberId()));
  }

  @Test
  void reminderSendsRenewalDueOncePerPeriod() {
    Ctx ctx = activeMemberWithSchedule("Reminder");
    openWindow(ctx.memberId());

    renewalReminderScheduler.runAllBatches();
    renewalReminderScheduler.runAllBatches();

    Long dueCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'RENEWAL_DUE'",
            Long.class,
            ctx.guest().getId());
    assertEquals(1L, dueCount, "exactly one RENEWAL_DUE per period");
  }

  /** Moves the clock to 2 days before the member's next billing, i.e. inside the renewal window. */
  private void openWindow(Long memberId) {
    LocalDateTime period = nextBillingAt(memberId);
    clock.set(period.minusDays(2).toInstant(ZoneOffset.UTC));
  }

  @TestConfiguration
  static class ClockTestConfig {
    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock(BASE_INSTANT, ZoneOffset.UTC);
    }
  }

  static final class MutableClock extends Clock {
    private final AtomicReference<Instant> instant;
    private final ZoneId zone;

    private MutableClock(Instant instant, ZoneId zone) {
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
      return new MutableClock(instant.get(), zone);
    }

    @Override
    public Instant instant() {
      return instant.get();
    }
  }
}
