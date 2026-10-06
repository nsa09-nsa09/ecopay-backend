package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.controller.AdminFinanceController;
import kz.hrms.splitupauth.dto.ConfirmOwnerAccessRequest;
import kz.hrms.splitupauth.dto.CreatePaymentIntentRequest;
import kz.hrms.splitupauth.dto.CreateRoomRequest;
import kz.hrms.splitupauth.dto.JoinRoomRequest;
import kz.hrms.splitupauth.dto.PaymentHistoryItemDto;
import kz.hrms.splitupauth.dto.PaymentIntentResponse;
import kz.hrms.splitupauth.dto.RegisterRequest;
import kz.hrms.splitupauth.dto.RoomMemberDto;
import kz.hrms.splitupauth.dto.RoomResponse;
import kz.hrms.splitupauth.entity.FreedomWebhookInbox;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import kz.hrms.splitupauth.entity.Payout;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.RoomType;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.ResourceConflictException;
import kz.hrms.splitupauth.payment.gateway.GatewayWebhookEvent;
import kz.hrms.splitupauth.payment.gateway.MockPaymentGateway;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayMessage;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayProperties;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPaySignatureService;
import kz.hrms.splitupauth.repository.FreedomWebhookInboxRepository;
import kz.hrms.splitupauth.repository.PaymentIntentRepository;
import kz.hrms.splitupauth.repository.PayoutRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
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

/**
 * End-to-end money flow against a real Postgres (Testcontainers) with the dev mock gateway: guest
 * joins a room → pays → the host's payout is created but <b>held</b> for the hold window (30 days
 * in prod) → the hold elapses → the dispatcher releases the money to the host's payout card.
 *
 * <p>The production hold is {@code app.payout.hold-days} (default 30). Waiting 30 real days in a
 * test is obviously not viable, so this test injects a mutable {@link Clock}, moves it past {@code
 * releaseAt}, and calls the dispatcher synchronously. Nothing about the dispatcher or gateway is
 * stubbed.
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
@Import(PaymentToPayoutE2EIntegrationTest.ClockTestConfig.class)
@org.springframework.test.context.TestPropertySource(
    properties = "app.payout.batch-coalesce-hours=0")
class PaymentToPayoutE2EIntegrationTest extends AbstractIntegrationTest {

  private static final Instant BASE_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

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
  @Autowired EntityManager entityManager;
  @Autowired MutableClock mutableClock;
  @Autowired MockPaymentGateway mockGateway;
  @Autowired PaymentHistoryService paymentHistoryService;
  @Autowired FreedomWebhookInboxCoordinator inboxCoordinator;
  @Autowired FreedomWebhookInboxTransactions inboxTransactions;
  @Autowired FreedomWebhookInboxRepository inboxRepository;
  @Autowired FreedomPayGateway freedomPayGateway;
  @Autowired FreedomPaySignatureService freedomPaySignatures;
  @Autowired FreedomPayProperties freedomPayProperties;
  @Autowired AdminFinanceController adminFinanceController;
  @Autowired AdminDashboardService adminDashboardService;

  private static final AtomicInteger SEQ = new AtomicInteger();

  @BeforeEach
  void resetClockAndGatewayCounters() {
    mutableClock.set(BASE_INSTANT);
    mockGateway.resetCounters();
    jdbcTemplate.update("UPDATE room_settings SET minimum_room_members = 4 WHERE id = 1");
  }

  @AfterEach
  void restoreRoomMinimum() {
    jdbcTemplate.update("UPDATE room_settings SET minimum_room_members = 5 WHERE id = 1");
  }

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
    Long existingCards =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payout_methods WHERE user_id = ? AND is_default = TRUE AND status = 'ACTIVE'",
            Long.class,
            owner.getId());
    if (existingCards != null && existingCards > 0) {
      return;
    }
    jdbcTemplate.update(
        "INSERT INTO payout_methods (user_id, provider_name, provider_card_token, pan_mask, "
            + "is_default, status, token_source, created_at) VALUES (?, 'mock', ?, '6666', TRUE, "
            + "'ACTIVE', 'PAYOUT_CARD_TOKEN', CURRENT_TIMESTAMP)",
        owner.getId(),
        "tok_e2e_" + owner.getId());
  }

  private Payout reload(Long payoutId) {
    return payoutRepository.findById(payoutId).orElseThrow();
  }

  private RoomResponse createDigitalRoom(User host, String title) {
    givePayoutCard(host);
    CreateRoomRequest create = new CreateRoomRequest();
    create.setServiceId(2L);
    create.setTariffPlanId(2L);
    create.setCategoryId(1L);
    create.setRoomType(RoomType.DIGITAL);
    create.setTitle(title);
    // RoomService validates against the system clock; keep this independent of the mutable payout
    // clock used to compress the hold window below.
    create.setStartDate(LocalDateTime.now().plusMonths(2));
    return roomService.createRoom(host, create);
  }

  private RoomMemberDto joinRoom(Long roomId, User guest, String email) {
    JoinRoomRequest join = new JoinRoomRequest();
    join.setConsentAccepted(true);
    join.setIdentifierValue(email);
    return roomMemberService.joinRoom(roomId, guest, join);
  }

  private PaymentIntentResponse pay(RoomMemberDto membership, User guest, String key) {
    CreatePaymentIntentRequest pay = new CreatePaymentIntentRequest();
    pay.setIdempotencyKey(key);
    return paymentService.createPaymentIntent(membership.getId(), guest, pay);
  }

  @Test
  void guestPayment_releasesMoneyToHost_afterHoldWindow() {
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
        payout.getReleaseAt().isAfter(LocalDateTime.now(mutableClock).plusDays(29)),
        "prod hold is ~30 days out, was: " + payout.getReleaseAt());

    // dispatcher must NOT pay a held payout early
    payoutService.processPendingPayouts();
    assertEquals(
        "PENDING",
        reload(payoutId).getStatus(),
        "held payout must not be dispatched before its release time");

    // ---------- compress the 30-day hold to 20 seconds ----------

    // still within the (now 20s) hold — money stays put

    // ---------- wait out the hold window ----------

    mutableClock.set(payout.getReleaseAt().toInstant(ZoneOffset.UTC).plusSeconds(1));

    // ---------- money is released to the host ----------
    payoutService.processPendingPayouts();
    entityManager.clear();
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

  @Test
  void twoGuestsRaceForLastSeat_onlyOneReservesAndGatewayIsCalledOnce() throws Exception {
    User host = registerVerified("Race Host");
    User firstGuest = registerVerified("Race Guest One");
    User secondGuest = registerVerified("Race Guest Two");
    RoomResponse room = createDigitalRoom(host, "Race Room");
    jdbcTemplate.update("UPDATE rooms SET max_members = 2 WHERE id = ?", room.getId());

    RoomMemberDto firstMember = joinRoom(room.getId(), firstGuest, "race-one@test.kz");
    RoomMemberDto secondMember = joinRoom(room.getId(), secondGuest, "race-two@test.kz");

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Callable<PaymentIntentResponse> firstTask =
        () -> {
          ready.countDown();
          start.await();
          return pay(firstMember, firstGuest, "race-seat-" + firstMember.getId());
        };
    Callable<PaymentIntentResponse> secondTask =
        () -> {
          ready.countDown();
          start.await();
          return pay(secondMember, secondGuest, "race-seat-" + secondMember.getId());
        };

    Future<PaymentIntentResponse> first = executor.submit(firstTask);
    Future<PaymentIntentResponse> second = executor.submit(secondTask);
    ready.await();
    start.countDown();

    int successes = 0;
    int roomFull = 0;
    for (Future<PaymentIntentResponse> future : List.of(first, second)) {
      try {
        assertEquals(PaymentIntentStatus.SUCCESS, future.get().getStatus());
        successes++;
      } catch (Exception ex) {
        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
        if (cause instanceof ResourceConflictException conflict
            && "ROOM_FULL".equals(conflict.getCode())) {
          roomFull++;
        } else {
          throw ex;
        }
      }
    }
    executor.shutdownNow();

    assertEquals(1, successes);
    assertEquals(1, roomFull);
    assertEquals(1, mockGateway.chargeAttempts(), "losing reservation must not call gateway");
  }

  @Test
  void repeatedIdempotencyKey_returnsOriginalIntentWithoutSecondCharge() {
    User host = registerVerified("Idempotent Host");
    User guest = registerVerified("Idempotent Guest");
    RoomResponse room = createDigitalRoom(host, "Idempotent Room");
    RoomMemberDto member = joinRoom(room.getId(), guest, "idempotent@test.kz");

    PaymentIntentResponse first = pay(member, guest, "same-key-" + member.getId());
    PaymentIntentResponse second = pay(member, guest, "same-key-" + member.getId());

    assertEquals(first.getId(), second.getId());
    assertEquals(first.getAmount(), second.getAmount());
    assertEquals(first.getCurrency(), second.getCurrency());
    assertEquals(PaymentIntentStatus.SUCCESS, second.getStatus());
    assertEquals(1, mockGateway.chargeAttempts());
    Long intentRows =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment_intents WHERE idempotency_key = ?",
            Long.class,
            "same-key-" + member.getId());
    assertEquals(1L, intentRows);
    Long reservationRows =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment_reservations WHERE payment_intent_id = ?",
            Long.class,
            first.getId());
    Long memberReservationRows =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment_reservations WHERE room_member_id = ?",
            Long.class,
            member.getId());
    Long chargeRows =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment_transactions "
                + "WHERE payment_intent_id = ? AND type = 'CHARGE' AND status = 'SUCCESS'",
            Long.class,
            first.getId());
    Long providerChargeRows =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment_transactions "
                + "WHERE external_transaction_id = ? AND type = 'CHARGE' AND status = 'SUCCESS'",
            Long.class,
            first.getExternalPaymentId());
    Long captureLedgerRows =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM money_ledger_entries "
                + "WHERE payment_intent_id = ? AND entry_type = 'PAYMENT_CAPTURE' "
                + "AND idempotency_key = ?",
            Long.class,
            first.getId(),
            "capture-intent-" + first.getId());
    Long platformFeeLedgerRows =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM money_ledger_entries "
                + "WHERE payment_intent_id = ? AND entry_type = 'PLATFORM_FEE' "
                + "AND idempotency_key = ?",
            Long.class,
            first.getId(),
            "platform-fee-intent-" + first.getId());
    Long ownerHoldLedgerRows =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM money_ledger_entries "
                + "WHERE payment_intent_id = ? AND entry_type = 'OWNER_HOLD' "
                + "AND idempotency_key = ?",
            Long.class,
            first.getId(),
            "owner-hold-intent-" + first.getId());
    Long payoutRows =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payouts WHERE triggering_payment_intent_id = ?",
            Long.class,
            first.getId());
    Long refundRows =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM refund_transactions rt "
                + "JOIN payment_transactions pt ON pt.id = rt.payment_transaction_id "
                + "WHERE pt.payment_intent_id = ?",
            Long.class,
            first.getId());
    assertEquals(1L, reservationRows);
    assertEquals(1L, memberReservationRows);
    assertEquals(1L, chargeRows);
    assertEquals(1L, providerChargeRows);
    assertEquals(1L, captureLedgerRows);
    assertEquals(1L, platformFeeLedgerRows);
    assertEquals(1L, ownerHoldLedgerRows);
    assertEquals(1L, payoutRows);
    assertEquals(0L, refundRows);
  }

  @Test
  void foreignIdempotencyKey_conflictsWithoutLeakingOrCharging() {
    User host = registerVerified("Foreign Key Host");
    User firstGuest = registerVerified("Foreign Key Guest One");
    User secondGuest = registerVerified("Foreign Key Guest Two");
    RoomResponse room = createDigitalRoom(host, "Foreign Key Room");
    RoomMemberDto firstMember = joinRoom(room.getId(), firstGuest, "foreign-one@test.kz");
    RoomMemberDto secondMember = joinRoom(room.getId(), secondGuest, "foreign-two@test.kz");
    String key = "foreign-key-" + firstMember.getId();

    PaymentIntentResponse original = pay(firstMember, firstGuest, key);
    ResourceConflictException conflict =
        assertThrows(ResourceConflictException.class, () -> pay(secondMember, secondGuest, key));

    assertEquals("IDEMPOTENCY_KEY_CONFLICT", conflict.getCode());
    assertEquals(1, mockGateway.chargeAttempts());
    Long sameKeyRows =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment_intents WHERE idempotency_key = ?", Long.class, key);
    assertEquals(1L, sameKeyRows);
    assertNotNull(original.getId());
  }

  @Test
  void duplicateWebhook_doesNotCreateSecondTransactionOrPayout() {
    User host = registerVerified("Webhook Host");
    User guest = registerVerified("Webhook Guest");
    RoomResponse room = createDigitalRoom(host, "Webhook Room");
    RoomMemberDto member = joinRoom(room.getId(), guest, "webhook@test.kz");
    PaymentIntentResponse response = pay(member, guest, "webhook-key-" + member.getId());
    PaymentIntent intent = paymentIntentRepository.findById(response.getId()).orElseThrow();

    GatewayWebhookEvent event =
        GatewayWebhookEvent.builder()
            .intentId(intent.getId())
            .resultStatus("SUCCESS")
            .amount(intent.getAmount())
            .currency("KZT")
            .externalPaymentId(intent.getExternalPaymentId())
            .providerRequestId("duplicate-webhook-" + intent.getId())
            .build();

    paymentService.applyWebhookEvent(event);
    paymentService.applyWebhookEvent(event);

    Long chargeRows =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment_transactions "
                + "WHERE payment_intent_id = ? AND type = 'CHARGE' AND status = 'SUCCESS'",
            Long.class,
            intent.getId());
    Long payoutRows =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payouts WHERE triggering_payment_intent_id = ?",
            Long.class,
            intent.getId());
    assertEquals(1L, chargeRows);
    assertEquals(1L, payoutRows);
  }

  /**
   * Required test #27: a genuine, correctly signed result callback that exhausted its retries (e.g.
   * a database outage) sits in DEAD_LETTER without touching money; an admin re-queue is audited,
   * applies it exactly once, and neither a later provider retry nor a second re-queue duplicates
   * it. Forged (invalid-signature) dead letters can never be re-queued.
   */
  @Test
  void deadLetteredWebhook_isRecoveredByAuditedRequeue_exactlyOnce() {
    String previousSecret = freedomPayProperties.getSecretKey();
    freedomPayProperties.setSecretKey("dlq-it-secret");
    try {
      User host = registerVerified("DLQ Host");
      User guest = registerVerified("DLQ Guest");
      User admin = registerVerified("DLQ Admin");
      RoomResponse room = createDigitalRoom(host, "DLQ Room");
      RoomMemberDto member = joinRoom(room.getId(), guest, "dlq@test.kz");
      mockGateway.setAsyncCapture(true);
      PaymentIntentResponse response = pay(member, guest, "dlq-key-" + member.getId());
      PaymentIntent intent = paymentIntentRepository.findById(response.getId()).orElseThrow();

      Map<String, String> callback = new LinkedHashMap<>();
      callback.put("pg_order_id", String.valueOf(intent.getId()));
      callback.put("pg_payment_id", intent.getExternalPaymentId());
      callback.put("pg_amount", intent.getAmount().toPlainString());
      callback.put("pg_currency", "KZT");
      callback.put("pg_result", "1");
      callback.put("pg_captured", "1");
      callback.put("pg_salt", "dlq-salt");
      callback.put(
          "pg_sig",
          freedomPaySignatures.sign(
              FreedomPayGateway.RESULT_SCRIPT, callback, freedomPayProperties.getSecretKey()));
      ObjectNode rawBody = JsonNodeFactory.instance.objectNode();
      callback.forEach(rawBody::put);
      FreedomWebhookInbox deadLetter =
          inboxTransactions.insert(
              FreedomWebhookInbox.builder()
                  .providerRequestId(
                      freedomPayGateway.callbackRequestId(
                          FreedomPayGateway.RESULT_SCRIPT, FreedomPayMessage.of(callback)))
                  .callbackScript(FreedomPayGateway.RESULT_SCRIPT)
                  .rawBody(rawBody)
                  .signatureValid(true)
                  .processingStatus("DEAD_LETTER")
                  .attemptCount(8)
                  .lastAttemptAt(LocalDateTime.now())
                  .processedAt(LocalDateTime.now())
                  .deadLetteredAt(LocalDateTime.now())
                  .lastErrorCode("DATABASE_ERROR")
                  .errorMessage("database unavailable")
                  .build());

      // Dead letters are never applied on their own, not even by the retry scheduler.
      inboxCoordinator.retryDueWebhooks();
      assertEquals(
          PaymentIntentStatus.PENDING,
          paymentIntentRepository.findById(intent.getId()).orElseThrow().getStatus());
      assertEquals(0L, successfulCharges(intent.getId()));

      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  admin, null, List.of(new SimpleGrantedAuthority("ADMIN"))));
      MockHttpServletRequest http = new MockHttpServletRequest();
      http.addHeader("User-Agent", "dlq-it");
      adminFinanceController.requeueWebhook(
          deadLetter.getId(),
          new AdminFinanceController.RequeueWebhookRequest("database recovered"),
          admin,
          http);

      FreedomWebhookInbox recovered = inboxRepository.findById(deadLetter.getId()).orElseThrow();
      assertEquals("PROCESSED", recovered.getProcessingStatus());
      assertEquals(
          PaymentIntentStatus.SUCCESS,
          paymentIntentRepository.findById(intent.getId()).orElseThrow().getStatus());
      assertEquals(1L, successfulCharges(intent.getId()));
      assertEquals(1L, payoutsFor(intent.getId()));
      assertEquals(
          1L,
          jdbcTemplate.queryForObject(
              "SELECT COUNT(*) FROM admin_action_log WHERE action_type = 'WEBHOOK_REQUEUED' "
                  + "AND entity_id = ?",
              Long.class,
              deadLetter.getId()));

      // The provider retries the same callback later, and an admin clicks re-queue again.
      FreedomWebhookInboxCoordinator.Acceptance retry =
          inboxCoordinator.acceptAndProcess(FreedomPayGateway.RESULT_SCRIPT, callback);
      assertEquals(deadLetter.getId(), retry.inboxId());
      assertNull(inboxTransactions.requeueDeadLetter(deadLetter.getId()));
      assertEquals(1L, successfulCharges(intent.getId()));
      assertEquals(1L, payoutsFor(intent.getId()));

      // A forged callback is dead-lettered at the door and cannot be re-queued into processing.
      Map<String, String> forged = new LinkedHashMap<>(callback);
      forged.put("pg_amount", "1.00");
      forged.put("pg_salt", "forged");
      FreedomWebhookInboxCoordinator.Acceptance rejected =
          inboxCoordinator.acceptAndProcess(FreedomPayGateway.RESULT_SCRIPT, forged);
      assertTrue(rejected.invalidSignature());
      assertNull(inboxTransactions.requeueDeadLetter(rejected.inboxId()));
      assertThrows(
          ResourceConflictException.class,
          () ->
              adminFinanceController.requeueWebhook(
                  rejected.inboxId(),
                  new AdminFinanceController.RequeueWebhookRequest("try forged"),
                  admin,
                  http));
      assertFalse(
          "PROCESSED"
              .equals(
                  inboxRepository
                      .findById(rejected.inboxId())
                      .orElseThrow()
                      .getProcessingStatus()));
      assertEquals(1L, successfulCharges(intent.getId()));
    } finally {
      SecurityContextHolder.clearContext();
      mockGateway.setAsyncCapture(false);
      freedomPayProperties.setSecretKey(previousSecret);
    }
  }

  private Long successfulCharges(Long intentId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM payment_transactions "
            + "WHERE payment_intent_id = ? AND type = 'CHARGE' AND status = 'SUCCESS'",
        Long.class,
        intentId);
  }

  private Long payoutsFor(Long intentId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM payouts WHERE triggering_payment_intent_id = ?",
        Long.class,
        intentId);
  }

  @Test
  void dashboardStartupKpis_countARealCapturedPayment_andAreComputedNotFaked() {
    var before = adminDashboardService.getKpis();
    User host = registerVerified("KPI Host");
    User guest = registerVerified("KPI Guest");
    RoomResponse room = createDigitalRoom(host, "KPI Room");
    RoomMemberDto member = joinRoom(room.getId(), guest, "kpi@test.kz");
    pay(member, guest, "kpi-key-" + member.getId());

    var after = adminDashboardService.getKpis();
    assertEquals(before.getSuccessfulPayments30d() + 1, after.getSuccessfulPayments30d());
    assertEquals(
        before.getUsersWithFirstSuccessfulPayment30d() + 1,
        after.getUsersWithFirstSuccessfulPayment30d());
    assertEquals(before.getRegistrations30d() + 2, after.getRegistrations30d());
    // The captured payment's owner share is now inside the 30-day payout reserve.
    assertTrue(after.getPayoutHeldAmountKzt().compareTo(before.getPayoutHeldAmountKzt()) > 0);
    assertNotNull(after.getFreedomWebhookDeadLetterCount());
    assertNotNull(after.getRefundRequiresReviewCount());
    double successRate = after.getPaymentSuccessRate30d();
    assertTrue(successRate > 0 && successRate <= 100, "rate is a percentage: " + successRate);
  }

  @Test
  void paymentHistory_returnsOnlyCurrentUsersOperations() {
    User host = registerVerified("History Host");
    User guest = registerVerified("History Guest");
    User otherGuest = registerVerified("History Other");
    RoomResponse room = createDigitalRoom(host, "History Room");
    RoomMemberDto member = joinRoom(room.getId(), guest, "history-one@test.kz");
    RoomMemberDto otherMember = joinRoom(room.getId(), otherGuest, "history-two@test.kz");

    PaymentIntentResponse ownPayment = pay(member, guest, "history-own-" + member.getId());
    PaymentIntentResponse otherPayment =
        pay(otherMember, otherGuest, "history-other-" + otherMember.getId());

    List<PaymentHistoryItemDto> items =
        paymentHistoryService.history(guest, 0, 50, null, null, null, null).getItems();

    assertTrue(
        items.stream().anyMatch(item -> ownPayment.getId().equals(item.getPaymentIntentId())));
    assertTrue(
        items.stream().noneMatch(item -> otherPayment.getId().equals(item.getPaymentIntentId())));
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

    void advance(Duration duration) {
      instant.updateAndGet(current -> current.plus(duration));
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
