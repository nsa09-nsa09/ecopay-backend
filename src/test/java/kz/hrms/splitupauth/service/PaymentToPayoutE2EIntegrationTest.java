package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
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
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import kz.hrms.splitupauth.entity.Payout;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.RoomType;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.repository.PaymentIntentRepository;
import kz.hrms.splitupauth.repository.PayoutRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end money flow against a real Postgres (Testcontainers) with the dev mock gateway: guest
 * joins a room → pays → the host's payout is created but <b>held</b> for the hold window (30 days
 * in prod) → the hold elapses → the dispatcher releases the money to the host's payout card.
 *
 * <p>The production hold is {@code app.payout.hold-days} (default 30). Waiting 30 real days in a
 * test is obviously not viable, so this test reproduces the exact same {@code releaseAt}-gated
 * dispatch logic but compresses the window to {@link #HOLD_SECONDS} seconds: after the guest pays,
 * the freshly-created payout (which the service stamped ~30 days out) has its {@code releaseAt}
 * rewritten to now + 20s, then the test waits out those 20 seconds and asserts the money is
 * released. Nothing about the dispatcher is stubbed — only the clock the hold is measured against
 * is shortened.
 *
 * <p>What is proven:
 *
 * <ol>
 *   <li>guest charge succeeds and the membership advances (APPLIED → PENDING → ACTIVE);
 *   <li>a host payout is created for the share ({@code charge − EcoPay commission}), status
 *       PENDING, held ~30 days out;
 *   <li>the dispatcher does <b>not</b> pay a held payout early;
 *   <li>once the (compressed) hold elapses, the dispatcher settles the payout SUCCESS to the host's
 *       card.
 * </ol>
 *
 * <p>Note on the sandbox test cards: they belong to the real Freedom Pay hosted payment page, where
 * card entry happens in the browser on the provider's redirect page — that can't be driven from a
 * headless JUnit test. This test therefore exercises the same backend money flow through the dev
 * mock gateway (the provider the whole chain is designed to be swapped behind); the cards are for
 * manual sandbox runs against a deployed environment.
 */
class PaymentToPayoutE2EIntegrationTest extends AbstractIntegrationTest {

  /** Compressed stand-in for the production 30-day payout hold window. */
  private static final int HOLD_SECONDS = 20;

  @Autowired AuthService authService;
  @Autowired PhoneVerificationService phoneVerificationService;
  @Autowired RoomService roomService;
  @Autowired RoomMemberService roomMemberService;
  @Autowired PaymentService paymentService;
  @Autowired PayoutService payoutService;
  @Autowired UserRepository userRepository;
  @Autowired RoomRepository roomRepository;
  @Autowired RoomMemberRepository roomMemberRepository;
  @Autowired PaymentIntentRepository paymentIntentRepository;
  @Autowired PayoutRepository payoutRepository;
  @Autowired JdbcTemplate jdbcTemplate;

  private static final AtomicInteger SEQ = new AtomicInteger();

  private User registerVerified(String name) {
    int n = SEQ.incrementAndGet();
    RegisterRequest req = new RegisterRequest();
    req.setEmail("e2e_" + n + "_" + System.nanoTime() + "@test.kz");
    req.setPassword("Test1234");
    req.setDisplayName(name);

    authService.register(req, MailLocale.RU, null);
    User user = userRepository.findByEmail(req.getEmail()).orElseThrow();
    String phone = "+77" + String.format("%09d", (System.nanoTime() % 1_000_000_000L));
    phoneVerificationService.requestCode(user, phone, null);
    phoneVerificationService.verifyCode(user, phone, "000000");
    return userRepository.findByEmail(req.getEmail()).orElseThrow();
  }

  /** Owners must have an active default payout card before the money can be dispatched to them. */
  private void givePayoutCard(User owner) {
    jdbcTemplate.update(
        "INSERT INTO payout_methods (user_id, provider_name, provider_card_token, pan_mask, "
            + "is_default, status, created_at) VALUES (?, 'mock', ?, '6666', TRUE, "
            + "'ACTIVE', CURRENT_TIMESTAMP)",
        owner.getId(),
        "tok_e2e_" + owner.getId());
  }

  private Payout reload(Long payoutId) {
    return payoutRepository.findById(payoutId).orElseThrow();
  }

  @Test
  void guestPayment_releasesMoneyToHost_afterHoldWindow() throws InterruptedException {
    // ---------- actors ----------
    User host = registerVerified("E2E Host");
    givePayoutCard(host);
    User guest = registerVerified("E2E Guest");

    // ---------- host creates a DIGITAL room (seeded Netflix service, V7) ----------
    CreateRoomRequest create = new CreateRoomRequest();
    create.setServiceId(2L);
    create.setTariffPlanId(2L);
    create.setCategoryId(1L);
    create.setRoomType(RoomType.DIGITAL);
    create.setTitle("E2E Netflix");
    // Seats/price/currency/period come from the seeded tariff plan (V10): 7290.00 / 4 monthly.
    create.setStartDate(LocalDateTime.now().plusMonths(2));
    RoomResponse room = roomService.createRoom(host, create);
    assertEquals(RoomStatus.OPEN, room.getStatus());

    // ---------- guest joins ----------
    JoinRoomRequest join = new JoinRoomRequest();
    join.setConsentAccepted(true);
    // Netflix is an EMAIL-access service (V54), so the member hands over an address.
    join.setIdentifierValue("e2e-guest@gmail.com");
    RoomMemberDto membership = roomMemberService.joinRoom(room.getId(), guest, join);
    assertEquals(MemberStatus.APPLIED, membership.getStatus());

    // ---------- guest pays (mock gateway → synchronous SUCCESS) ----------
    CreatePaymentIntentRequest pay = new CreatePaymentIntentRequest();
    pay.setIdempotencyKey("e2e-pay-" + membership.getId());
    PaymentIntentResponse intent =
        paymentService.createPaymentIntent(membership.getId(), guest, pay);
    assertEquals(PaymentIntentStatus.SUCCESS, intent.getStatus());
    // Share = 7290.00 / 4 = 1822.50; EcoPay commission for that tier (<= 4000) = 500.00;
    // the guest is charged share + commission = 2322.50.
    assertEquals(0, new BigDecimal("1822.50").compareTo(intent.getShareAmount()));
    assertEquals(0, new BigDecimal("500.00").compareTo(intent.getCommissionAmount()));
    assertEquals(0, new BigDecimal("2322.50").compareTo(intent.getAmount()));
    assertEquals(
        MemberStatus.PENDING,
        roomMemberRepository.findById(membership.getId()).orElseThrow().getStatus());

    // ---------- happy path to ACTIVE (host grants → guest confirms) ----------
    ConfirmOwnerAccessRequest grant = new ConfirmOwnerAccessRequest();
    grant.setAccessMethod("invite_link");
    roomMemberService.confirmOwnerAccess(room.getId(), membership.getId(), host, grant);
    roomMemberService.confirmMemberAccess(room.getId(), guest);
    assertEquals(
        MemberStatus.ACTIVE,
        roomMemberRepository.findById(membership.getId()).orElseThrow().getStatus());
    assertEquals(
        RoomStatus.ACTIVE, roomRepository.findById(room.getId()).orElseThrow().getStatus());

    // ---------- the host payout was created, but HELD ----------
    PaymentIntent intentEntity = paymentIntentRepository.findById(intent.getId()).orElseThrow();
    Payout payout = payoutRepository.findByTriggeringPaymentIntent(intentEntity).orElseThrow();
    Long payoutId = payout.getId();

    // The host is paid the full share; EcoPay keeps the commission.
    // payout = amount - commission = 2322.50 - 500.00 = 1822.50
    BigDecimal expectedPayout = new BigDecimal("1822.50");
    assertEquals(
        0,
        expectedPayout.compareTo(payout.getAmount()),
        "host payout should be the share (charge minus EcoPay commission)");
    assertEquals("PENDING", payout.getStatus());
    assertNotNull(payout.getReleaseAt(), "payout must carry a hold/release timestamp");
    assertTrue(
        payout.getReleaseAt().isAfter(LocalDateTime.now().plusDays(29)),
        "prod hold is ~30 days out, was: " + payout.getReleaseAt());

    // dispatcher must NOT pay a held payout early
    payoutService.processPendingPayouts();
    assertEquals(
        "PENDING",
        reload(payoutId).getStatus(),
        "held payout must not be dispatched before its release time");

    // ---------- compress the 30-day hold to 20 seconds ----------
    Payout toCompress = payoutRepository.findById(payoutId).orElseThrow();
    toCompress.setReleaseAt(LocalDateTime.now().plusSeconds(HOLD_SECONDS));
    payoutRepository.save(toCompress);

    // still within the (now 20s) hold — money stays put
    payoutService.processPendingPayouts();
    assertEquals(
        "PENDING",
        reload(payoutId).getStatus(),
        "payout must still be held during the (compressed) window");

    // ---------- wait out the hold window ----------
    Thread.sleep((HOLD_SECONDS + 1) * 1000L);

    // ---------- money is released to the host ----------
    payoutService.processPendingPayouts();
    Payout paid = reload(payoutId);
    assertEquals(
        "SUCCESS",
        paid.getStatus(),
        "after the hold elapses the payout should be dispatched to the host");
    assertNotNull(paid.getProviderPayoutId(), "a settled payout must carry the provider payout id");
    assertNotNull(paid.getProcessedAt(), "a settled payout must be timestamped");
    assertEquals(
        0,
        expectedPayout.compareTo(paid.getAmount()),
        "the amount released to the host must be unchanged");
  }
}
