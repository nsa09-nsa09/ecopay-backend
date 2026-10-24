package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import kz.hrms.splitupauth.dto.PayoutBalanceDto;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.Payout;
import kz.hrms.splitupauth.entity.PayoutBatch;
import kz.hrms.splitupauth.entity.PayoutMethod;
import kz.hrms.splitupauth.entity.PayoutStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.payment.gateway.GatewayPayoutResponse;
import kz.hrms.splitupauth.payment.gateway.GatewayStatusResponse;
import kz.hrms.splitupauth.payment.gateway.PaymentGateway;
import kz.hrms.splitupauth.payment.gateway.PaymentGatewayRegistry;
import kz.hrms.splitupauth.repository.PayoutBatchRepository;
import kz.hrms.splitupauth.repository.PayoutMethodRepository;
import kz.hrms.splitupauth.repository.PayoutRepository;
import kz.hrms.splitupauth.repository.SavedCardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class PayoutServiceTest {

  @Mock private PayoutRepository payoutRepository;
  @Mock private PayoutBatchRepository payoutBatchRepository;
  @Mock private PayoutMethodRepository payoutMethodRepository;
  @Mock private PaymentGatewayRegistry gatewayRegistry;
  @Mock private PaymentGateway paymentGateway;
  @Mock private PaymentEventLogger eventLogger;
  @Mock private SavedCardRepository savedCardRepository;
  @Mock private NotificationService notificationService;
  @Mock private MoneyLedgerService moneyLedgerService;
  @Mock private PayoutEligibilityService payoutEligibilityService;
  @Mock private PayoutBalanceService payoutBalanceService;
  @Mock private PlatformTransactionManager transactionManager;

  private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"));

  private PayoutService payoutService;

  @BeforeEach
  void setUp() {
    lenient()
        .when(transactionManager.getTransaction(any()))
        .thenReturn(new SimpleTransactionStatus());
    payoutService =
        new PayoutService(
            payoutRepository,
            payoutBatchRepository,
            payoutMethodRepository,
            gatewayRegistry,
            eventLogger,
            savedCardRepository,
            notificationService,
            moneyLedgerService,
            payoutEligibilityService,
            payoutBalanceService,
            clock,
            transactionManager);
    org.springframework.test.util.ReflectionTestUtils.setField(
        payoutService, "payoutBatchCoalesceHours", 0);
    lenient()
        .when(payoutEligibilityService.evaluate(any()))
        .thenReturn(new PayoutEligibilityService.Decision(true, false, null));
    lenient().when(payoutBatchRepository.findRetryableForDispatch(any())).thenReturn(List.of());
    lenient()
        .when(payoutBatchRepository.findProviderPendingForReconciliation(any()))
        .thenReturn(List.of());
  }

  @Test
  void reverse_pendingPayout_fullRefund_marksReversed() {
    PaymentIntent intent = new PaymentIntent();
    Payout payout = Payout.builder().status(PayoutStatus.PENDING).idempotencyKey("k").build();
    when(payoutRepository.findByTriggeringPaymentIntent(intent)).thenReturn(Optional.of(payout));

    payoutService.reverseOwnerPayoutForRefund(intent, true);

    assertEquals(PayoutStatus.REVERSED, payout.getStatus());
    verify(payoutRepository).save(payout);
  }

  @Test
  void reverse_alreadyPaidPayout_isNotReversed_flaggedInstead() {
    PaymentIntent intent = new PaymentIntent();
    Payout payout = Payout.builder().status(PayoutStatus.SUCCESS).idempotencyKey("k").build();
    when(payoutRepository.findByTriggeringPaymentIntent(intent)).thenReturn(Optional.of(payout));

    payoutService.reverseOwnerPayoutForRefund(intent, true);

    // Already dispatched/paid: must NOT silently flip status; left for manual clawback.
    assertEquals(PayoutStatus.SUCCESS, payout.getStatus());
    verify(payoutRepository, never()).save(any());
  }

  @Test
  void reverse_partialRefund_onPendingPayout_isNotAutoReversed() {
    PaymentIntent intent = new PaymentIntent();
    Payout payout = Payout.builder().status(PayoutStatus.PENDING).idempotencyKey("k").build();
    when(payoutRepository.findByTriggeringPaymentIntent(intent)).thenReturn(Optional.of(payout));

    payoutService.reverseOwnerPayoutForRefund(intent, false);

    // Partial refund needs an accounting decision — flagged, not auto-reversed.
    assertEquals(PayoutStatus.PENDING, payout.getStatus());
    verify(payoutRepository, never()).save(any());
  }

  @Test
  void successfulPartialRefundReducesFutureOwnerPayout() {
    PaymentIntent intent =
        PaymentIntent.builder()
            .amount(new BigDecimal("5700.00"))
            .commissionAmount(new BigDecimal("700.00"))
            .build();
    Payout payout =
        Payout.builder()
            .id(123L)
            .status(PayoutStatus.FROZEN)
            .amount(new BigDecimal("5000.00"))
            .originalAmount(new BigDecimal("5000.00"))
            .payableAmount(new BigDecimal("5000.00"))
            .idempotencyKey("payout-123")
            .build();
    when(payoutRepository.findByTriggeringPaymentIntent(intent)).thenReturn(Optional.of(payout));
    when(payoutRepository.findWithLockById(123L)).thenReturn(Optional.of(payout));

    payoutService.adjustOwnerPayoutForSuccessfulRefund(intent, new BigDecimal("2850.00"));

    assertEquals(0, new BigDecimal("2500.00").compareTo(payout.getRefundedShareAmount()));
    assertEquals(0, new BigDecimal("2500.00").compareTo(payout.getPayableAmount()));
    assertEquals(0, new BigDecimal("2500.00").compareTo(payout.getAmount()));
    assertEquals(PayoutStatus.FROZEN, payout.getStatus());
    verify(payoutRepository).save(payout);
  }

  @Test
  void freedomPaySynchronousAcceptanceWaitsForFinalCallback() {
    User owner = User.builder().id(42L).build();
    Payout payout =
        Payout.builder()
            .id(124L)
            .user(owner)
            .amount(new BigDecimal("1000.00"))
            .currency("KZT")
            .status(PayoutStatus.PENDING)
            .idempotencyKey("payout-124")
            .releaseAt(LocalDateTime.now(clock).minusSeconds(1))
            .build();
    PayoutMethod method =
        PayoutMethod.builder()
            .user(owner)
            .providerCardToken("tok-owner")
            .tokenSource(PayoutMethod.TOKEN_SOURCE_PAYOUT_CARD)
            .status("ACTIVE")
            .build();
    when(payoutRepository.findWithLockById(124L)).thenReturn(Optional.of(payout));
    when(payoutMethodRepository.findByUserAndIsDefaultTrueAndStatus(owner, "ACTIVE"))
        .thenReturn(Optional.of(method));
    when(payoutRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(gatewayRegistry.defaultGateway()).thenReturn(paymentGateway);
    when(paymentGateway.providerName()).thenReturn("freedompay");
    when(paymentGateway.payout(any()))
        .thenReturn(
            GatewayPayoutResponse.builder().success(true).externalPayoutId("FP-124").build());

    payoutService.dispatchPayout(124L);

    assertEquals(PayoutStatus.PENDING_PROVIDER, payout.getStatus());
    verify(moneyLedgerService, never())
        .append(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void reconcilePendingProviderPayout_finalSuccessSettlesWithoutResending() {
    User owner = User.builder().id(42L).build();
    Payout payout =
        Payout.builder()
            .id(125L)
            .user(owner)
            .amount(new BigDecimal("1000.00"))
            .currency("KZT")
            .status(PayoutStatus.PENDING_PROVIDER)
            .providerPayoutId("FP-125")
            .build();
    when(payoutRepository.findProviderPendingForReconciliation(any())).thenReturn(List.of(payout));
    when(payoutRepository.findWithLockById(125L)).thenReturn(Optional.of(payout));
    when(gatewayRegistry.defaultGateway()).thenReturn(paymentGateway);
    when(paymentGateway.getPayoutStatus("FP-125", "125"))
        .thenReturn(
            GatewayStatusResponse.builder().externalPaymentId("FP-125").status("SUCCESS").build());

    payoutService.reconcilePendingProviderPayouts();

    assertEquals(PayoutStatus.SUCCESS, payout.getStatus());
    assertNotNull(payout.getProcessedAt());
    verify(paymentGateway, never()).payout(any());
    verify(payoutRepository).save(payout);
  }

  @Test
  void reconcilePendingProviderPayout_finalFailureRequiresReview() {
    Payout payout =
        Payout.builder()
            .id(126L)
            .amount(new BigDecimal("1000.00"))
            .currency("KZT")
            .status(PayoutStatus.PENDING_PROVIDER)
            .providerPayoutId("FP-126")
            .build();
    when(payoutRepository.findProviderPendingForReconciliation(any())).thenReturn(List.of(payout));
    when(payoutRepository.findWithLockById(126L)).thenReturn(Optional.of(payout));
    when(gatewayRegistry.defaultGateway()).thenReturn(paymentGateway);
    when(paymentGateway.getPayoutStatus("FP-126", "126"))
        .thenReturn(
            GatewayStatusResponse.builder()
                .externalPaymentId("FP-126")
                .status("FAILED")
                .failureMessage("declined")
                .build());

    payoutService.reconcilePendingProviderPayouts();

    assertEquals(PayoutStatus.REQUIRES_REVIEW, payout.getStatus());
    assertEquals("declined", payout.getFailureReason());
    verify(paymentGateway, never()).payout(any());
  }

  @Test
  void reverse_nullIntent_isNoop() {
    payoutService.reverseOwnerPayoutForRefund(null, true);
    verifyNoInteractions(payoutRepository);
  }

  @Test
  void heldBalance_sumsCurrentHeldPayoutsAndFindsNextRelease() {
    User owner = User.builder().id(42L).build();
    LocalDateTime laterRelease = LocalDateTime.now().plusDays(12);
    LocalDateTime nextRelease = LocalDateTime.now().plusDays(5);
    when(payoutRepository.findByUserAndCurrencyAndStatusInAndReleaseAtAfterOrderByReleaseAtAsc(
            any(), any(), any(), any()))
        .thenReturn(
            List.of(
                Payout.builder()
                    .amount(new BigDecimal("9999.00"))
                    .payableAmount(new BigDecimal("1500.25"))
                    .releaseAt(laterRelease)
                    .build(),
                Payout.builder()
                    .amount(new BigDecimal("9999.00"))
                    .payableAmount(new BigDecimal("499.75"))
                    .releaseAt(nextRelease)
                    .build()));

    PayoutBalanceDto balance = payoutService.getHeldBalance(owner);

    assertEquals(0, new BigDecimal("2000.00").compareTo(balance.getHeldAmount()));
    assertEquals("KZT", balance.getCurrency());
    assertEquals(2L, balance.getHeldPayoutCount());
    assertEquals(nextRelease, balance.getNextReleaseAt());
    assertNotNull(balance.getCalculatedAt());
  }

  @Test
  void processPendingPayouts_beforeReleaseAt_staysPending() {
    when(payoutRepository.findDispatchable(any(), any())).thenReturn(List.of());

    payoutService.processPendingPayouts();

    verify(payoutRepository).findDispatchable(any(), any());
    verifyNoInteractions(gatewayRegistry);
  }

  @Test
  void processPendingPayouts_afterReleaseAt_dispatchesSuccessfully() {
    User owner = User.builder().id(42L).build();
    Payout payout =
        Payout.builder()
            .id(77L)
            .user(owner)
            .amount(new BigDecimal("1000.00"))
            .currency("KZT")
            .status(PayoutStatus.PENDING)
            .idempotencyKey("payout-77")
            .releaseAt(LocalDateTime.now(clock).minusSeconds(1))
            .build();
    PayoutMethod method =
        PayoutMethod.builder()
            .user(owner)
            .providerCardToken("tok-owner")
            .tokenSource(PayoutMethod.TOKEN_SOURCE_PAYOUT_CARD)
            .status("ACTIVE")
            .build();
    when(payoutRepository.findDispatchable(any(), any())).thenReturn(List.of(payout));
    when(payoutRepository.findWithLockById(77L)).thenReturn(Optional.of(payout));
    when(payoutMethodRepository.findByUserAndIsDefaultTrueAndStatus(owner, "ACTIVE"))
        .thenReturn(Optional.of(method));
    when(payoutRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(gatewayRegistry.defaultGateway()).thenReturn(paymentGateway);
    when(paymentGateway.payout(any()))
        .thenReturn(
            GatewayPayoutResponse.builder().success(true).externalPayoutId("MOCK-OUT-77").build());

    payoutService.processPendingPayouts();

    assertEquals(PayoutStatus.SUCCESS, payout.getStatus());
    assertEquals("MOCK-OUT-77", payout.getProviderPayoutId());
    assertEquals(null, payout.getLeaseUntil());
    assertNotNull(payout.getProcessedAt());
    verify(paymentGateway, times(1)).payout(any());
  }

  @Test
  void processPendingPayouts_threeEligibleSameOwnerMethodCurrency_dispatchesOneBatch() {
    User owner = User.builder().id(42L).build();
    LocalDateTime due = LocalDateTime.now(clock).minusHours(1);
    Payout p1 = payout(1L, owner, "1000.00", due);
    Payout p2 = payout(2L, owner, "1500.50", due);
    Payout p3 = payout(3L, owner, "499.50", due);
    PayoutMethod method =
        PayoutMethod.builder()
            .id(5L)
            .user(owner)
            .providerCardToken("tok-owner")
            .tokenSource(PayoutMethod.TOKEN_SOURCE_PAYOUT_CARD)
            .status("ACTIVE")
            .build();
    AtomicReference<PayoutBatch> savedBatch = new AtomicReference<>();

    when(payoutRepository.findDispatchable(any(), any())).thenReturn(List.of(p1, p2, p3));
    when(payoutRepository.findWithLockById(1L)).thenReturn(Optional.of(p1));
    when(payoutRepository.findWithLockById(2L)).thenReturn(Optional.of(p2));
    when(payoutRepository.findWithLockById(3L)).thenReturn(Optional.of(p3));
    when(payoutRepository.findWithLockByIdIn(List.of(1L, 2L, 3L))).thenReturn(List.of(p1, p2, p3));
    when(payoutMethodRepository.findByUserAndIsDefaultTrueAndStatus(owner, "ACTIVE"))
        .thenReturn(Optional.of(method));
    when(payoutBatchRepository.save(any(PayoutBatch.class)))
        .thenAnswer(
            invocation -> {
              PayoutBatch batch = invocation.getArgument(0);
              if (batch.getId() == null) {
                batch.setId(900L);
              }
              savedBatch.set(batch);
              return batch;
            });
    when(payoutBatchRepository.findWithLockById(900L))
        .thenAnswer(invocation -> Optional.of(savedBatch.get()));
    when(payoutRepository.findWithLockByPayoutBatchId(900L)).thenReturn(List.of(p1, p2, p3));
    when(payoutRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(gatewayRegistry.defaultGateway()).thenReturn(paymentGateway);
    when(paymentGateway.payout(any()))
        .thenReturn(
            GatewayPayoutResponse.builder().success(true).externalPayoutId("MOCK-BATCH").build());

    payoutService.processPendingPayouts();

    verify(paymentGateway)
        .payout(
            argThat(
                request ->
                    request.getPayoutId().equals(900L)
                        && request.getIdempotencyKey().startsWith("payout-batch-")
                        && new BigDecimal("3000.00").compareTo(request.getAmount()) == 0));
    assertEquals("SUCCESS", savedBatch.get().getStatus());
    assertEquals(PayoutStatus.SUCCESS, p1.getStatus());
    assertEquals(PayoutStatus.SUCCESS, p2.getStatus());
    assertEquals(PayoutStatus.SUCCESS, p3.getStatus());
  }

  @Test
  void dispatchPayout_activeProcessingLease_isNotSentAgain() {
    Payout payout = Payout.builder().id(88L).status(PayoutStatus.PROCESSING).build();
    when(payoutRepository.findWithLockById(88L)).thenReturn(Optional.of(payout));

    payoutService.dispatchPayout(88L);

    verifyNoInteractions(gatewayRegistry);
  }

  @Test
  void dispatchPayout_expiredProcessingLease_isRecoveredAndSent() {
    User owner = User.builder().id(42L).build();
    Payout payout =
        Payout.builder()
            .id(89L)
            .user(owner)
            .amount(new BigDecimal("1000.00"))
            .currency("KZT")
            .status(PayoutStatus.PROCESSING)
            .idempotencyKey("payout-89")
            .leaseUntil(LocalDateTime.now(clock).minusMinutes(1))
            .build();
    PayoutMethod method =
        PayoutMethod.builder()
            .user(owner)
            .providerCardToken("tok-owner")
            .tokenSource(PayoutMethod.TOKEN_SOURCE_PAYOUT_CARD)
            .status("ACTIVE")
            .build();
    when(payoutRepository.findWithLockById(89L)).thenReturn(Optional.of(payout));
    when(payoutMethodRepository.findByUserAndIsDefaultTrueAndStatus(owner, "ACTIVE"))
        .thenReturn(Optional.of(method));
    when(payoutRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(gatewayRegistry.defaultGateway()).thenReturn(paymentGateway);
    when(paymentGateway.payout(any()))
        .thenReturn(
            GatewayPayoutResponse.builder().success(true).externalPayoutId("MOCK-OUT-89").build());

    payoutService.dispatchPayout(89L);

    assertEquals(PayoutStatus.SUCCESS, payout.getStatus());
    verify(paymentGateway, times(1)).payout(any());
  }

  @Test
  void dispatchPayout_afterSuccess_isIdempotentAndDoesNotCreateSecondGatewayPayout() {
    User owner = User.builder().id(42L).build();
    Payout payout =
        Payout.builder()
            .id(99L)
            .user(owner)
            .amount(new BigDecimal("1000.00"))
            .currency("KZT")
            .status(PayoutStatus.PENDING)
            .idempotencyKey("payout-99")
            .releaseAt(LocalDateTime.now(clock).minusSeconds(1))
            .build();
    PayoutMethod method =
        PayoutMethod.builder()
            .user(owner)
            .providerCardToken("tok-owner")
            .tokenSource(PayoutMethod.TOKEN_SOURCE_PAYOUT_CARD)
            .status("ACTIVE")
            .build();
    when(payoutRepository.findWithLockById(99L)).thenReturn(Optional.of(payout));
    when(payoutMethodRepository.findByUserAndIsDefaultTrueAndStatus(owner, "ACTIVE"))
        .thenReturn(Optional.of(method));
    when(payoutRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(gatewayRegistry.defaultGateway()).thenReturn(paymentGateway);
    when(paymentGateway.payout(any()))
        .thenReturn(
            GatewayPayoutResponse.builder().success(true).externalPayoutId("MOCK-OUT-99").build());

    payoutService.dispatchPayout(99L);
    payoutService.dispatchPayout(99L);

    assertEquals(PayoutStatus.SUCCESS, payout.getStatus());
    verify(paymentGateway, times(1)).payout(any());
  }

  @Test
  void dispatchPayout_retryWithPreviousOrderAtProvider_skipsResendViaPredispatchCheck() {
    User owner = User.builder().id(42L).build();
    Payout payout =
        Payout.builder()
            .id(400L)
            .user(owner)
            .amount(new BigDecimal("1000.00"))
            .payableAmount(new BigDecimal("1000.00"))
            .currency("KZT")
            .status(PayoutStatus.PENDING)
            .idempotencyKey("payout-400")
            .releaseAt(LocalDateTime.now(clock).minusHours(1))
            .submittedAmount(new BigDecimal("1000.00"))
            .providerOrderId("ecopay-payout-400")
            .retryCount(1)
            .nextRetryAt(LocalDateTime.now(clock).minusSeconds(1))
            .build();
    PayoutMethod method =
        PayoutMethod.builder()
            .user(owner)
            .providerCardToken("tok-owner")
            .tokenSource(PayoutMethod.TOKEN_SOURCE_PAYOUT_CARD)
            .status("ACTIVE")
            .build();
    when(payoutRepository.findWithLockById(400L)).thenReturn(Optional.of(payout));
    when(payoutMethodRepository.findByUserAndIsDefaultTrueAndStatus(owner, "ACTIVE"))
        .thenReturn(Optional.of(method));
    when(payoutRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(gatewayRegistry.defaultGateway()).thenReturn(paymentGateway);
    when(paymentGateway.getPayoutStatus(null, "ecopay-payout-400"))
        .thenReturn(
            GatewayStatusResponse.builder().externalPaymentId("FP-400").status("PENDING").build());

    payoutService.dispatchPayout(400L);

    assertEquals(PayoutStatus.PENDING_PROVIDER, payout.getStatus());
    assertEquals("ecopay-payout-400", payout.getProviderOrderId());
    assertEquals("FP-400", payout.getProviderPayoutId());
    verify(paymentGateway, never()).payout(any());
  }

  @Test
  void dispatchPayout_providerCardError_goesToPendingMethod() {
    User owner = User.builder().id(42L).build();
    Payout payout = duePayout(401L, owner);
    PayoutMethod method =
        PayoutMethod.builder()
            .user(owner)
            .providerCardToken("tok-owner")
            .tokenSource(PayoutMethod.TOKEN_SOURCE_PAYOUT_CARD)
            .status("ACTIVE")
            .build();
    when(payoutRepository.findWithLockById(401L)).thenReturn(Optional.of(payout));
    when(payoutMethodRepository.findByUserAndIsDefaultTrueAndStatus(owner, "ACTIVE"))
        .thenReturn(Optional.of(method));
    when(payoutRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(gatewayRegistry.defaultGateway()).thenReturn(paymentGateway);
    when(paymentGateway.providerName()).thenReturn("freedompay");
    when(paymentGateway.payout(any()))
        .thenReturn(
            GatewayPayoutResponse.builder()
                .success(false)
                .pending(false)
                .providerStatusCode("error")
                .failureCode("300")
                .failureMessage("Card expired or invalid")
                .build());

    payoutService.dispatchPayout(401L);

    assertEquals(PayoutStatus.PENDING_METHOD, payout.getStatus());
    assertNotNull(payout.getFailureReason());
  }

  @Test
  void processPendingPayouts_insufficientBalance_sendsWhatFitsAndHoldsTheRest() {
    // Balance 1000, three due payouts of 600 for three different owners: only the first fits.
    User o1 = User.builder().id(501L).build();
    User o2 = User.builder().id(502L).build();
    User o3 = User.builder().id(503L).build();
    Payout p1 = payout(601L, o1, "600.00", LocalDateTime.now(clock).minusHours(1));
    Payout p2 = payout(602L, o2, "600.00", LocalDateTime.now(clock).minusHours(1));
    Payout p3 = payout(603L, o3, "600.00", LocalDateTime.now(clock).minusHours(1));
    for (User owner : List.of(o1, o2, o3)) {
      PayoutMethod method =
          PayoutMethod.builder()
              .id(owner.getId())
              .user(owner)
              .providerCardToken("tok-" + owner.getId())
              .tokenSource(PayoutMethod.TOKEN_SOURCE_PAYOUT_CARD)
              .status("ACTIVE")
              .build();
      when(payoutMethodRepository.findByUserAndIsDefaultTrueAndStatus(owner, "ACTIVE"))
          .thenReturn(Optional.of(method));
    }
    when(payoutRepository.findDispatchable(any(), any())).thenReturn(List.of(p1, p2, p3));
    when(payoutRepository.findWithLockById(601L)).thenReturn(Optional.of(p1));
    when(payoutRepository.findWithLockById(602L)).thenReturn(Optional.of(p2));
    when(payoutRepository.findWithLockById(603L)).thenReturn(Optional.of(p3));
    when(payoutRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(payoutBalanceService.availableForDispatch()).thenReturn(new BigDecimal("1000.00"));
    when(gatewayRegistry.defaultGateway()).thenReturn(paymentGateway);
    when(paymentGateway.providerName()).thenReturn("mock");
    when(paymentGateway.payout(any()))
        .thenReturn(
            GatewayPayoutResponse.builder().success(true).externalPayoutId("MOCK-OUT").build());

    payoutService.processPendingPayouts();

    assertEquals(PayoutStatus.SUCCESS, p1.getStatus());
    assertEquals(PayoutStatus.ON_HOLD, p2.getStatus());
    assertEquals(PayoutStatus.ON_HOLD, p3.getStatus());
    assertEquals("MERCHANT_BALANCE_LOW", p2.getFailureReason());
    verify(paymentGateway, times(1)).payout(any());
    verify(payoutBalanceService).alertBalanceLow();
  }

  @Test
  void processPendingPayouts_unknownBalance_doesNotGate() {
    User owner = User.builder().id(42L).build();
    Payout payout = payout(701L, owner, "600.00", LocalDateTime.now(clock).minusHours(1));
    PayoutMethod method =
        PayoutMethod.builder()
            .user(owner)
            .providerCardToken("tok-owner")
            .tokenSource(PayoutMethod.TOKEN_SOURCE_PAYOUT_CARD)
            .status("ACTIVE")
            .build();
    when(payoutRepository.findDispatchable(any(), any())).thenReturn(List.of(payout));
    when(payoutRepository.findWithLockById(701L)).thenReturn(Optional.of(payout));
    when(payoutMethodRepository.findByUserAndIsDefaultTrueAndStatus(owner, "ACTIVE"))
        .thenReturn(Optional.of(method));
    when(payoutRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(payoutBalanceService.availableForDispatch()).thenReturn(null);
    when(gatewayRegistry.defaultGateway()).thenReturn(paymentGateway);
    when(paymentGateway.providerName()).thenReturn("mock");
    when(paymentGateway.payout(any()))
        .thenReturn(
            GatewayPayoutResponse.builder().success(true).externalPayoutId("MOCK-OUT").build());

    payoutService.processPendingPayouts();

    assertEquals(PayoutStatus.SUCCESS, payout.getStatus());
    verify(paymentGateway, times(1)).payout(any());
  }

  @Test
  void revokeLastActiveCard_parksPendingPayoutsAsPendingMethod() {
    User owner = User.builder().id(42L).build();
    PayoutMethod method =
        PayoutMethod.builder()
            .id(10L)
            .user(owner)
            .providerCardToken("tok-owner")
            .panMask("440043******0000")
            .tokenSource(PayoutMethod.TOKEN_SOURCE_PAYOUT_CARD)
            .status("ACTIVE")
            .isDefault(true)
            .build();
    Payout pending = payout(801L, owner, "500.00", LocalDateTime.now(clock).minusDays(1));
    Payout onHold = payout(802L, owner, "600.00", LocalDateTime.now(clock).minusDays(1));
    onHold.setStatus(PayoutStatus.ON_HOLD);
    when(payoutMethodRepository.findById(10L)).thenReturn(Optional.of(method));
    when(payoutMethodRepository.findByUserAndStatusOrderByIsDefaultDescCreatedAtDesc(
            owner, "ACTIVE"))
        .thenReturn(List.of(method));
    when(payoutMethodRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(payoutRepository.findByUserAndStatusIn(
            owner, List.of(PayoutStatus.PENDING, PayoutStatus.ON_HOLD)))
        .thenReturn(List.of(pending, onHold));
    when(payoutRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(gatewayRegistry.defaultGateway()).thenReturn(paymentGateway);

    int affected = payoutService.revokeMethod(owner, 10L);

    assertEquals(2, affected);
    assertEquals(PayoutStatus.PENDING_METHOD, pending.getStatus());
    assertEquals(PayoutStatus.PENDING_METHOD, onHold.getStatus());
    assertEquals("REVOKED", method.getStatus());
    verify(paymentGateway).deletePayoutCardToken("tok-owner");
  }

  @Test
  void revokeNonLastCard_leavesPendingPayoutsAlone() {
    User owner = User.builder().id(42L).build();
    PayoutMethod method =
        PayoutMethod.builder()
            .id(10L)
            .user(owner)
            .providerCardToken("tok-owner")
            .tokenSource(PayoutMethod.TOKEN_SOURCE_PAYOUT_CARD)
            .status("ACTIVE")
            .build();
    PayoutMethod other =
        PayoutMethod.builder()
            .id(11L)
            .user(owner)
            .providerCardToken("tok-2")
            .status("ACTIVE")
            .build();
    when(payoutMethodRepository.findById(10L)).thenReturn(Optional.of(method));
    when(payoutMethodRepository.findByUserAndStatusOrderByIsDefaultDescCreatedAtDesc(
            owner, "ACTIVE"))
        .thenReturn(List.of(method, other));
    when(payoutMethodRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(gatewayRegistry.defaultGateway()).thenReturn(paymentGateway);

    int affected = payoutService.revokeMethod(owner, 10L);

    assertEquals(0, affected);
    verify(payoutRepository, never()).findByUserAndStatusIn(any(), any());
  }

  @Test
  void registerVerifiedPayoutMethod_duplicateMaskReusesExistingMethod() {
    User owner = User.builder().id(42L).build();
    PayoutMethod existing =
        PayoutMethod.builder()
            .id(10L)
            .user(owner)
            .providerCardToken("tok-old")
            .panMask("440043******0000")
            .tokenSource(PayoutMethod.TOKEN_SOURCE_PAYOUT_CARD)
            .status("ACTIVE")
            .isDefault(true)
            .build();
    when(payoutMethodRepository.findByUserAndProviderCardTokenAndStatus(owner, "tok-new", "ACTIVE"))
        .thenReturn(Optional.empty());
    when(payoutMethodRepository.findByUserAndStatusOrderByIsDefaultDescCreatedAtDesc(
            owner, "ACTIVE"))
        .thenReturn(List.of(existing));
    when(payoutRepository.findByUserAndStatusIn(owner, List.of(PayoutStatus.PENDING_METHOD)))
        .thenReturn(List.of());

    PayoutMethod result =
        payoutService.registerVerifiedPayoutMethod(owner, "tok-new", "440043******0000");

    assertEquals(10L, result.getId());
    verify(payoutMethodRepository, never()).save(any());
  }

  @Test
  void registerVerifiedPayoutMethod_firstCardReactivatesPendingMethodPayouts() {
    User owner = User.builder().id(42L).build();
    Payout waiting = payout(810L, owner, "500.00", LocalDateTime.now(clock).minusDays(1));
    waiting.setStatus(PayoutStatus.PENDING_METHOD);
    when(payoutMethodRepository.findByUserAndProviderCardTokenAndStatus(owner, "tok-new", "ACTIVE"))
        .thenReturn(Optional.empty());
    when(payoutMethodRepository.findByUserAndStatusOrderByIsDefaultDescCreatedAtDesc(
            owner, "ACTIVE"))
        .thenReturn(List.of());
    when(payoutMethodRepository.findByUserAndIsDefaultTrueAndStatus(owner, "ACTIVE"))
        .thenReturn(Optional.empty());
    when(payoutMethodRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(payoutRepository.findByUserAndStatusIn(owner, List.of(PayoutStatus.PENDING_METHOD)))
        .thenReturn(List.of(waiting));
    when(payoutRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    payoutService.registerVerifiedPayoutMethod(owner, "tok-new", "440043******1111");

    assertEquals(PayoutStatus.PENDING, waiting.getStatus());
    assertEquals(LocalDateTime.now(clock), waiting.getNextRetryAt());
  }

  @Test
  void reconcilePendingProviderPayout_stuckOver24h_goesToReview() {
    Payout payout =
        Payout.builder()
            .id(900L)
            .amount(new BigDecimal("1000.00"))
            .submittedAmount(new BigDecimal("1000.00"))
            .currency("KZT")
            .status(PayoutStatus.PENDING_PROVIDER)
            .providerPayoutId("FP-900")
            .providerOrderId("ecopay-payout-900")
            .submittedAt(LocalDateTime.now(clock).minusHours(25))
            .build();
    when(payoutRepository.findProviderPendingForReconciliation(any())).thenReturn(List.of(payout));
    when(payoutRepository.findWithLockById(900L)).thenReturn(Optional.of(payout));
    when(payoutRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(gatewayRegistry.defaultGateway()).thenReturn(paymentGateway);
    when(paymentGateway.getPayoutStatus("FP-900", "ecopay-payout-900"))
        .thenReturn(GatewayStatusResponse.builder().status("PENDING").build());

    payoutService.reconcilePendingProviderPayouts();

    assertEquals(PayoutStatus.REQUIRES_REVIEW, payout.getStatus());
    verify(paymentGateway, never()).payout(any());
  }

  @Test
  void retryStuckPayout_requeuesToPendingForNextTick() {
    Payout payout =
        Payout.builder()
            .id(901L)
            .amount(new BigDecimal("1000.00"))
            .currency("KZT")
            .status(PayoutStatus.REQUIRES_REVIEW)
            .idempotencyKey("payout-901")
            .retryCount(3)
            .build();
    when(payoutRepository.findWithLockById(901L)).thenReturn(Optional.of(payout));
    when(payoutRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    payoutService.retryStuckPayout(901L);

    assertEquals(PayoutStatus.PENDING, payout.getStatus());
    assertEquals(0, payout.getRetryCount());
    assertEquals(LocalDateTime.now(clock), payout.getNextRetryAt());
  }

  @Test
  void dispatchPayout_absorbFee_sendsFullAmountAndBooksFeeExpense() {
    org.springframework.test.util.ReflectionTestUtils.setField(
        payoutService, "providerFeeMode", "ABSORB");
    org.springframework.test.util.ReflectionTestUtils.setField(
        payoutService, "providerFeeFixed", new BigDecimal("50.00"));
    User owner = User.builder().id(42L).build();
    Payout payout = payout(950L, owner, "500.00", LocalDateTime.now(clock).minusHours(1));
    PayoutMethod method =
        PayoutMethod.builder()
            .user(owner)
            .providerCardToken("tok-owner")
            .tokenSource(PayoutMethod.TOKEN_SOURCE_PAYOUT_CARD)
            .status("ACTIVE")
            .build();
    when(payoutRepository.findWithLockById(950L)).thenReturn(Optional.of(payout));
    when(payoutMethodRepository.findByUserAndIsDefaultTrueAndStatus(owner, "ACTIVE"))
        .thenReturn(Optional.of(method));
    when(payoutRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(gatewayRegistry.defaultGateway()).thenReturn(paymentGateway);
    when(paymentGateway.providerName()).thenReturn("mock");
    when(paymentGateway.payout(any()))
        .thenReturn(GatewayPayoutResponse.builder().success(true).externalPayoutId("M").build());

    payoutService.dispatchPayout(950L);

    assertEquals(PayoutStatus.SUCCESS, payout.getStatus());
    assertEquals(0, new BigDecimal("500.00").compareTo(payout.getSubmittedAmount()));
    assertEquals(0, new BigDecimal("50.00").compareTo(payout.getProviderFeeKzt()));
    verify(paymentGateway)
        .payout(argThat(r -> new BigDecimal("500.00").compareTo(r.getAmount()) == 0));
    verify(moneyLedgerService)
        .append(
            eq("PAYOUT_FEE_EXPENSE"),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any());
  }

  @Test
  void dispatchPayout_deductFee_reducesAmountSentToOwner() {
    org.springframework.test.util.ReflectionTestUtils.setField(
        payoutService, "providerFeeMode", "DEDUCT");
    org.springframework.test.util.ReflectionTestUtils.setField(
        payoutService, "providerFeeFixed", new BigDecimal("50.00"));
    User owner = User.builder().id(42L).build();
    Payout payout = payout(951L, owner, "500.00", LocalDateTime.now(clock).minusHours(1));
    PayoutMethod method =
        PayoutMethod.builder()
            .user(owner)
            .providerCardToken("tok-owner")
            .tokenSource(PayoutMethod.TOKEN_SOURCE_PAYOUT_CARD)
            .status("ACTIVE")
            .build();
    when(payoutRepository.findWithLockById(951L)).thenReturn(Optional.of(payout));
    when(payoutMethodRepository.findByUserAndIsDefaultTrueAndStatus(owner, "ACTIVE"))
        .thenReturn(Optional.of(method));
    when(payoutRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(gatewayRegistry.defaultGateway()).thenReturn(paymentGateway);
    when(paymentGateway.providerName()).thenReturn("mock");
    when(paymentGateway.payout(any()))
        .thenReturn(GatewayPayoutResponse.builder().success(true).externalPayoutId("M").build());

    payoutService.dispatchPayout(951L);

    assertEquals(0, new BigDecimal("450.00").compareTo(payout.getSubmittedAmount()));
    assertEquals(0, new BigDecimal("50.00").compareTo(payout.getProviderFeeKzt()));
    verify(paymentGateway)
        .payout(argThat(r -> new BigDecimal("450.00").compareTo(r.getAmount()) == 0));
  }

  @Test
  void processPendingPayouts_belowMinimum_defersInsteadOfSending() {
    org.springframework.test.util.ReflectionTestUtils.setField(
        payoutService, "minPayoutAmount", new BigDecimal("1000.00"));
    User owner = User.builder().id(42L).build();
    Payout small = payout(952L, owner, "300.00", LocalDateTime.now(clock).minusHours(1));
    PayoutMethod method =
        PayoutMethod.builder()
            .id(5L)
            .user(owner)
            .providerCardToken("tok-owner")
            .tokenSource(PayoutMethod.TOKEN_SOURCE_PAYOUT_CARD)
            .status("ACTIVE")
            .build();
    when(payoutRepository.findDispatchable(any(), any())).thenReturn(List.of(small));
    when(payoutRepository.findWithLockById(952L)).thenReturn(Optional.of(small));
    when(payoutMethodRepository.findByUserAndIsDefaultTrueAndStatus(owner, "ACTIVE"))
        .thenReturn(Optional.of(method));
    when(payoutRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    payoutService.processPendingPayouts();

    assertEquals("BELOW_MIN_AMOUNT", small.getDeferredReason());
    assertEquals(PayoutStatus.PENDING, small.getStatus());
    verify(paymentGateway, never()).payout(any());
  }

  private Payout payout(Long id, User owner, String amount, LocalDateTime releaseAt) {
    return Payout.builder()
        .id(id)
        .user(owner)
        .amount(new BigDecimal(amount))
        .payableAmount(new BigDecimal(amount))
        .currency("KZT")
        .status(PayoutStatus.PENDING)
        .idempotencyKey("payout-" + id)
        .releaseAt(releaseAt)
        .build();
  }

  private Payout duePayout(long id, User owner) {
    return Payout.builder()
        .id(id)
        .user(owner)
        .amount(new BigDecimal("1000.00"))
        .payableAmount(new BigDecimal("1000.00"))
        .currency("KZT")
        .status(PayoutStatus.PENDING)
        .idempotencyKey("payout-" + id)
        .releaseAt(LocalDateTime.now(clock).minusSeconds(1))
        .build();
  }

  @Test
  void legacyPurchaseTokenMethodIsNeverAPayoutDestination() {
    User owner = User.builder().id(42L).build();
    Payout payout = duePayout(301L, owner);
    PayoutMethod legacy =
        PayoutMethod.builder()
            .user(owner)
            .providerCardToken("recurring-profile")
            .status("ACTIVE")
            .build();
    when(payoutRepository.findWithLockById(301L)).thenReturn(Optional.of(payout));
    when(payoutMethodRepository.findByUserAndIsDefaultTrueAndStatus(owner, "ACTIVE"))
        .thenReturn(Optional.of(legacy));
    when(payoutRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    payoutService.dispatchPayout(301L);

    assertEquals(PayoutStatus.PENDING_METHOD, payout.getStatus());
    verify(paymentGateway, never()).payout(any());
  }

  @Test
  void payoutTimeoutAfterProviderAcceptedIsReconciledNeverResent() {
    User owner = User.builder().id(42L).build();
    Payout payout = duePayout(302L, owner);
    PayoutMethod method =
        PayoutMethod.builder()
            .user(owner)
            .providerCardToken("payout-token")
            .tokenSource(PayoutMethod.TOKEN_SOURCE_PAYOUT_CARD)
            .status("ACTIVE")
            .build();
    when(payoutRepository.findWithLockById(302L)).thenReturn(Optional.of(payout));
    when(payoutMethodRepository.findByUserAndIsDefaultTrueAndStatus(owner, "ACTIVE"))
        .thenReturn(Optional.of(method));
    when(payoutRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(gatewayRegistry.defaultGateway()).thenReturn(paymentGateway);
    when(paymentGateway.providerName()).thenReturn("freedompay");
    when(paymentGateway.payout(any())).thenThrow(new IllegalStateException("read timeout"));

    payoutService.dispatchPayout(302L);
    assertEquals(PayoutStatus.PENDING_PROVIDER, payout.getStatus());
    assertEquals(null, payout.getProviderPayoutId());
    assertEquals(LocalDateTime.now(clock), payout.getSubmittedAt());

    // A second dispatcher pass must not send it again.
    payoutService.dispatchPayout(302L);
    verify(paymentGateway, times(1)).payout(any());

    when(payoutRepository.findProviderPendingForReconciliation(any())).thenReturn(List.of(payout));
    when(paymentGateway.getPayoutStatus(null, "ecopay-payout-302"))
        .thenReturn(
            GatewayStatusResponse.builder()
                .externalPaymentId("FP-302")
                .status("SUCCESS")
                .amount(new BigDecimal("1000.00"))
                .build());
    payoutService.reconcilePendingProviderPayouts();

    assertEquals(PayoutStatus.SUCCESS, payout.getStatus());
    assertEquals("FP-302", payout.getProviderPayoutId());
    verify(paymentGateway, times(1)).payout(any());
  }

  @Test
  void duplicatePayoutCallbackSettlesOnceAndWritesLedgerOnce() {
    User owner = User.builder().id(42L).build();
    Payout payout = duePayout(303L, owner);
    payout.setStatus(PayoutStatus.PENDING_PROVIDER);
    payout.setSubmittedAmount(new BigDecimal("1000.00"));
    payout.setProviderPayoutId("FP-303");
    when(payoutRepository.findWithLockByProviderPayoutId("FP-303")).thenReturn(Optional.of(payout));
    when(payoutRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    payoutService.applyPayoutWebhook(
        "FP-303", "ecopay-payout-303", true, new BigDecimal("1000.00"));
    payoutService.applyPayoutWebhook(
        "FP-303", "ecopay-payout-303", true, new BigDecimal("1000.00"));

    assertEquals(PayoutStatus.SUCCESS, payout.getStatus());
    verify(moneyLedgerService, times(2))
        .append(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void payoutCallbackWithDifferentAmountGoesToReview() {
    User owner = User.builder().id(42L).build();
    Payout payout = duePayout(304L, owner);
    payout.setStatus(PayoutStatus.PENDING_PROVIDER);
    payout.setSubmittedAmount(new BigDecimal("1000.00"));
    when(payoutRepository.findWithLockByProviderPayoutId("FP-304")).thenReturn(Optional.empty());
    when(payoutRepository.findWithLockByProviderOrderId("ecopay-payout-304"))
        .thenReturn(Optional.of(payout));
    when(payoutBatchRepository.findWithLockByProviderPayoutId("FP-304"))
        .thenReturn(Optional.empty());
    when(payoutBatchRepository.findWithLockByProviderOrderId("ecopay-payout-304"))
        .thenReturn(Optional.empty());
    when(payoutRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    payoutService.applyPayoutWebhook(
        "FP-304", "ecopay-payout-304", true, new BigDecimal("9999.00"));

    assertEquals(PayoutStatus.REQUIRES_REVIEW, payout.getStatus());
    assertEquals("FP-304", payout.getProviderPayoutId());
    verify(moneyLedgerService, never())
        .append(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void ownerPayoutReserveIsCapturedAtPlusConfiguredHold() {
    org.springframework.test.util.ReflectionTestUtils.setField(payoutService, "payoutHoldDays", 30);
    User owner = User.builder().id(42L).build();
    kz.hrms.splitupauth.entity.Room room =
        kz.hrms.splitupauth.entity.Room.builder().id(5L).owner(owner).build();
    kz.hrms.splitupauth.entity.RoomMember member =
        kz.hrms.splitupauth.entity.RoomMember.builder().id(6L).room(room).build();
    LocalDateTime capturedAt = LocalDateTime.of(2025, 12, 20, 10, 0);
    PaymentIntent intent =
        PaymentIntent.builder()
            .id(77L)
            .roomMember(member)
            .amount(new BigDecimal("2322.50"))
            .commissionAmount(new BigDecimal("500.00"))
            .capturedAt(capturedAt)
            .build();
    when(payoutRepository.findByTriggeringPaymentIntent(intent)).thenReturn(Optional.empty());
    when(payoutRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    Payout payout = payoutService.createOwnerPayoutForSuccessfulPayment(intent);

    assertEquals(capturedAt.plusDays(30), payout.getReleaseAt());
    assertEquals(new BigDecimal("1822.50"), payout.getAmount());
    assertEquals(PayoutStatus.PENDING, payout.getStatus());
  }
}
