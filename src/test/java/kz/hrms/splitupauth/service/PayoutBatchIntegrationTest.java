package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.payment.gateway.*;
import kz.hrms.splitupauth.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL, Flyway, eligibility, refunds and ledger; only the provider boundary is mocked. */
@Import(PayoutBatchIntegrationTest.Config.class)
@TestPropertySource(properties = "app.payout.batch-coalesce-hours=24")
class PayoutBatchIntegrationTest extends AbstractIntegrationTest {
  @Autowired PayoutService service;
  @Autowired RefundService refunds;
  @Autowired PayoutRepository payouts;
  @Autowired PayoutBatchRepository batches;
  @Autowired UserRepository users;
  @Autowired RoomRepository rooms;
  @Autowired ServiceRepository services;
  @Autowired RoomMemberRepository members;
  @Autowired PaymentIntentRepository intents;
  @Autowired PaymentTransactionRepository charges;
  @Autowired RefundTransactionRepository refundTransactions;
  @Autowired PayoutMethodRepository methods;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired TestClock clock;
  @MockitoBean PaymentGatewayRegistry registry;
  PaymentGateway provider;
  String pendingId;
  final List<Long> created = new ArrayList<>();
  final List<GatewayPayoutRequest> sent = new CopyOnWriteArrayList<>();

  @BeforeEach void setup() {
    clock.now.set(Instant.parse("2020-01-01T00:00:00Z"));
    pendingId = "pending-" + UUID.randomUUID();
    provider = mock(PaymentGateway.class);
    when(registry.defaultGateway()).thenReturn(provider);
    when(provider.providerName()).thenReturn("test-provider");
    when(provider.payout(any())).thenAnswer(call -> {
      GatewayPayoutRequest request = call.getArgument(0);
      sent.add(request);
      return success(request);
    });
  }

  @AfterEach void isolateRemainingPayouts() {
    for (Long id : created) {
      jdbc.update("UPDATE payouts SET status = 'REQUIRES_REVIEW' WHERE id = ? AND status <> 'SUCCESS'", id);
      jdbc.update("UPDATE payout_batches SET status = 'REQUIRES_REVIEW' WHERE id = (SELECT payout_batch_id FROM payouts WHERE id = ?) AND status <> 'SUCCESS'", id);
    }
  }

  @Test void singletonWaitsUntilCoalescingEndsThenPaysOnce() {
    Payout p = due(owner(), "KZT");
    service.processPendingPayouts();
    assertTrue(sent.isEmpty());
    assertEquals("PENDING", reload(p).getStatus());
    clock.advance(Duration.ofHours(24).minusSeconds(1));
    service.processPendingPayouts();
    assertTrue(sent.isEmpty());
    clock.advance(Duration.ofSeconds(1));
    service.processPendingPayouts();
    service.processPendingPayouts();
    assertEquals(1, sent.size());
    assertEquals("SUCCESS", reload(p).getStatus());
    assertEquals("ecopay-payout-" + p.getId(), sent.get(0).getProviderOrderId());
  }

  @Test void compatiblePayoutsProduceOneProviderTransferAndExactLedger() {
    User owner = owner();
    Payout a = due(owner, "KZT"), b = due(owner, "KZT"), c = due(owner, "KZT");
    service.processPendingPayouts();
    service.processPendingPayouts();
    assertEquals(1, sent.size());
    assertEquals(new BigDecimal("4500.00"), sent.get(0).getAmount());
    assertNotNull(reload(a).getPayoutBatch());
    assertEquals(reload(a).getPayoutBatch().getId(), reload(b).getPayoutBatch().getId());
    assertEquals(reload(a).getPayoutBatch().getId(), reload(c).getPayoutBatch().getId());
    assertLedgerMatches(sent.get(0), 3);
    // Direct dispatch cannot bypass the child's batch membership.
    service.dispatchPayout(a.getId());
    assertEquals(1, sent.size());
  }

  @Test void differentOwnersAndCurrenciesAreNeverCombined() {
    User a = owner(), b = owner();
    due(a, "KZT"); due(a, "USD"); due(b, "KZT");
    service.processPendingPayouts();
    assertTrue(sent.isEmpty());
    clock.advance(Duration.ofHours(24));
    service.processPendingPayouts();
    assertEquals(3, sent.size());
    assertTrue(sent.stream().allMatch(r -> r.getAmount().compareTo(new BigDecimal("1500")) == 0));
  }

  @Test void pendingReconcilesToSuccessWithoutResendEvenAfterDuplicateCallback() {
    User owner = owner();
    Payout a = due(owner, "KZT"); due(owner, "KZT");
    pending();
    service.processPendingPayouts();
    assertEquals("PENDING_PROVIDER", batch(a).getStatus());
    clock.advance(Duration.ofMinutes(6));
    when(provider.getPayoutStatus(pendingId, batch(a).getProviderOrderId()))
        .thenReturn(GatewayStatusResponse.builder().status("SUCCESS").build());
    service.processPendingPayouts();
    service.reconcilePendingProviderPayouts();
    service.applyPayoutWebhook(pendingId, true);
    assertEquals(1, sent.size());
    assertLedgerMatches(sent.get(0), 2);
  }

  @Test void refundBeforeBatchCreationReducesFrozenAmount() {
    User owner = owner();
    Payout a = due(owner, "KZT"); due(owner, "KZT");
    refund(a, "975.00");
    service.processPendingPayouts();
    assertEquals(new BigDecimal("2250.00"), sent.get(0).getAmount());
    assertEquals(new BigDecimal("750.00"), reload(a).getSubmittedAmount());
    assertLedgerMatches(sent.get(0), 2);
  }

  @Test void refundAfterCommittedClaimBeforeProviderResponseRequiresClawback() {
    User owner = owner();
    Payout a = due(owner, "KZT"); due(owner, "KZT");
    when(provider.payout(any())).thenAnswer(call -> {
      // Claim has committed, but no definitive submission response exists yet.
      assertNotNull(batch(a).getSubmissionStartedAt());
      refund(a, "1950.00");
      GatewayPayoutRequest request = call.getArgument(0);
      sent.add(request);
      return success(request);
    });
    service.processPendingPayouts();
    assertTrue(reload(a).getClawbackRequired());
    assertEquals(new BigDecimal("1500.00"), reload(a).getSubmittedAmount());
    assertLedgerMatches(sent.get(0), 2);
  }

  @Test void refundWhileProviderPendingPreservesSettlementAndReviewFlag() {
    User owner = owner();
    Payout a = due(owner, "KZT"); due(owner, "KZT");
    pending();
    service.processPendingPayouts();
    refund(a, "1950.00");
    service.applyPayoutWebhook(pendingId, true);
    assertTrue(reload(a).getClawbackRequired());
    assertEquals("SUCCESS", reload(a).getStatus());
    assertLedgerMatches(sent.get(0), 2);
  }

  @Test void refundAfterSuccessDoesNotRewriteLedger() {
    User owner = owner();
    Payout a = due(owner, "KZT"); due(owner, "KZT");
    service.processPendingPayouts();
    refund(a, "1950.00");
    assertTrue(reload(a).getClawbackRequired());
    assertLedgerMatches(sent.get(0), 2);
    service.processPendingPayouts();
    assertEquals(1, sent.size());
  }

  @Test void transportRetryUsesSameBatchAndFrozenPayloadAfterRefundAndCardChange() {
    User owner = owner();
    Payout a = due(owner, "KZT"); due(owner, "KZT");
    when(provider.supportsIdempotentPayoutReplay()).thenReturn(true);
    when(provider.payout(any())).thenAnswer(call -> {
      GatewayPayoutRequest request = call.getArgument(0);
      sent.add(request);
      if (sent.size() == 1) throw new IllegalStateException("connection reset after send");
      return success(request);
    });
    service.processPendingPayouts();
    Long batchId = batch(a).getId();
    refund(a, "975.00");
    jdbc.update("UPDATE payout_methods SET provider_card_token = 'changed-token' WHERE user_id = ?", owner.getId());
    clock.advance(Duration.ofMinutes(6));
    // The retry reads persisted state in new transactions, without retaining the original claim.
    service.processPendingPayouts();
    service.processPendingPayouts();
    assertEquals(2, sent.size());
    assertEquals(sent.get(0), sent.get(1));
    assertEquals(batchId, batch(a).getId());
    assertTrue(reload(a).getClawbackRequired());
    assertLedgerMatches(sent.get(1), 2);
  }

  @Test void ambiguousProviderWithoutReplayGuaranteeGoesToReviewWithoutResend() {
    User owner = owner();
    Payout a = due(owner, "KZT"); due(owner, "KZT");
    when(provider.payout(any())).thenThrow(new IllegalStateException("timeout"));
    service.processPendingPayouts();
    clock.advance(Duration.ofDays(1));
    service.processPendingPayouts();
    verify(provider, times(1)).payout(any());
    assertEquals("REQUIRES_REVIEW", batch(a).getStatus());
    assertEquals("REQUIRES_REVIEW", reload(a).getStatus());
  }

  @Test void concurrentSchedulersCannotCreateDuplicateBatches() throws Exception {
    User owner = owner();
    Payout a = due(owner, "KZT"); due(owner, "KZT");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      Callable<Void> run = () -> { start.await(); service.processPendingPayouts(); return null; };
      Future<Void> first = pool.submit(run), second = pool.submit(run);
      start.countDown();
      first.get(30, TimeUnit.SECONDS); second.get(30, TimeUnit.SECONDS);
    } finally { pool.shutdownNow(); }
    assertEquals(1, sent.size());
    assertLedgerMatches(sent.get(0), 2);
    assertEquals("SUCCESS", batch(a).getStatus());
  }

  @Test void databaseRejectsMutationOfSubmittedPayloadAndInvalidRoomCounts() {
    User owner = owner();
    Payout a = due(owner, "KZT"); due(owner, "KZT");
    service.processPendingPayouts();
    assertThrows(org.springframework.dao.DataAccessException.class,
        () -> jdbc.update("UPDATE payouts SET submitted_amount = 1 WHERE id = ?", a.getId()));
    assertThrows(org.springframework.dao.DataAccessException.class,
        () -> jdbc.update("UPDATE payout_batches SET amount = 1 WHERE id = ?", batch(a).getId()));
    for (int invalid : List.of(0, 3, 4, 5)) {
      assertThrows(org.springframework.dao.DataAccessException.class,
          () -> jdbc.update("UPDATE rooms SET existing_members_count = ? WHERE id = ?", invalid, a.getRoom().getId()));
    }
    assertThrows(org.springframework.dao.DataAccessException.class,
        () -> jdbc.update("UPDATE rooms SET max_members = 2, existing_members_count = 2 WHERE id = ?", a.getRoom().getId()));
  }

  private void pending() {
    when(provider.payout(any())).thenAnswer(call -> {
      sent.add(call.getArgument(0));
      return GatewayPayoutResponse.builder().pending(true).externalPayoutId(pendingId).build();
    });
  }

  private GatewayPayoutResponse success(GatewayPayoutRequest request) {
    return GatewayPayoutResponse.builder().success(true).externalPayoutId("provider-" + request.getIdempotencyKey()).build();
  }

  private User owner() {
    User user = users.save(User.builder().email(UUID.randomUUID() + "@test.kz").password("test")
        .displayName("Batch owner").role(Role.USER).status(UserStatus.ACTIVE).build());
    methods.save(PayoutMethod.builder().user(user).providerName("test-provider")
        .providerCardToken("token-" + user.getId()).isDefault(true).status("ACTIVE").build());
    return user;
  }

  private Payout due(User owner, String currency) {
    Payout p = new TransactionTemplate(transactions).execute(status -> {
      Room room = rooms.save(Room.builder().owner(owner).service(services.findById(2L).orElseThrow())
          .roomType(RoomType.DIGITAL).verificationMode(VerificationMode.RISK_BASED).status(RoomStatus.ACTIVE)
          .title("Batch test").maxMembers(5).existingMembersCount(2).currency("KZT")
          .priceTotal(new BigDecimal("7500.00")).pricePerMember(new BigDecimal("1500.00"))
          .periodType(PeriodType.MONTHLY).startDate(LocalDateTime.now(clock).minusDays(31)).build());
      User guest = users.save(User.builder().email(UUID.randomUUID() + "@test.kz").password("test")
          .displayName("Guest").role(Role.USER).status(UserStatus.ACTIVE).build());
      RoomMember member = members.save(RoomMember.builder().room(room).user(guest).status(MemberStatus.ACTIVE)
          .activatedAt(LocalDateTime.now(clock)).ownerAccessConfirmedAt(LocalDateTime.now(clock))
          .memberConfirmedAt(LocalDateTime.now(clock)).build());
      PaymentIntent intent = intents.save(PaymentIntent.builder().roomMember(member).user(guest)
          .amount(new BigDecimal("1950.00")).commissionAmount(new BigDecimal("450.00"))
          .status(PaymentIntentStatus.SUCCESS).capturedAt(LocalDateTime.now(clock).minusDays(30))
          .idempotencyKey(UUID.randomUUID().toString()).build());
      charges.save(PaymentTransaction.builder().paymentIntent(intent).room(room).roomMember(member)
          .type(PaymentTransactionType.CHARGE).status(PaymentTransactionStatus.SUCCESS)
          .amount(intent.getAmount()).currency(currency).build());
      Payout payout = service.createOwnerPayoutForSuccessfulPayment(intent);
      payout.setCurrency(currency);
      return payouts.save(payout);
    });
    created.add(p.getId());
    return p;
  }

  private void refund(Payout payout, String amount) {
    String external = "refund-" + UUID.randomUUID();
    new TransactionTemplate(transactions).executeWithoutResult(status -> {
      PaymentIntent intent = intents.findById(payout.getTriggeringPaymentIntent().getId()).orElseThrow();
      PaymentTransaction charge = charges.findFirstByPaymentIntentAndTypeAndStatus(intent,
          PaymentTransactionType.CHARGE, PaymentTransactionStatus.SUCCESS).orElseThrow();
      refundTransactions.save(RefundTransaction.builder().paymentTransaction(charge)
          .amount(new BigDecimal(amount)).status(RefundStatus.PENDING_PROVIDER).reason("Batch test")
          .providerRefundId(external).idempotencyKey(external).build());
    });
    refunds.applyRefundWebhook(external, true);
  }

  private Payout reload(Payout p) { return payouts.findById(p.getId()).orElseThrow(); }
  private PayoutBatch batch(Payout p) { return batches.findById(reload(p).getPayoutBatch().getId()).orElseThrow(); }
  private void assertLedgerMatches(GatewayPayoutRequest request, int children) {
    BigDecimal total = jdbc.queryForObject("SELECT SUM(l.amount) FROM money_ledger_entries l JOIN payouts p ON p.id = l.payout_id WHERE p.payout_batch_id = ? AND l.entry_type = 'PAYOUT'", BigDecimal.class, request.getPayoutId());
    assertEquals(request.getAmount(), total);
    assertEquals(children, jdbc.queryForObject("SELECT COUNT(*) FROM money_ledger_entries l JOIN payouts p ON p.id = l.payout_id WHERE p.payout_batch_id = ? AND l.entry_type = 'PAYOUT'", Integer.class, request.getPayoutId()));
  }

  @TestConfiguration static class Config {
    @Bean @Primary TestClock testClock() { return new TestClock(); }
  }
  static class TestClock extends Clock {
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2020-01-01T00:00:00Z"));
    void advance(Duration duration) { now.updateAndGet(t -> t.plus(duration)); }
    public ZoneId getZone() { return ZoneOffset.UTC; }
    public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
    public Instant instant() { return now.get(); }
  }
}
