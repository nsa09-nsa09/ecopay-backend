package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
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
import kz.hrms.splitupauth.dto.PayoutBalanceDto;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.Payout;
import kz.hrms.splitupauth.entity.PayoutMethod;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.payment.gateway.GatewayPayoutResponse;
import kz.hrms.splitupauth.payment.gateway.PaymentGateway;
import kz.hrms.splitupauth.payment.gateway.PaymentGatewayRegistry;
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
  @Mock private PayoutMethodRepository payoutMethodRepository;
  @Mock private PaymentGatewayRegistry gatewayRegistry;
  @Mock private PaymentGateway paymentGateway;
  @Mock private PaymentEventLogger eventLogger;
  @Mock private SavedCardRepository savedCardRepository;
  @Mock private NotificationService notificationService;
  @Mock private MoneyLedgerService moneyLedgerService;
  @Mock private PlatformTransactionManager transactionManager;

  private final Clock clock =
      Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"));

  private PayoutService payoutService;

  @BeforeEach
  void setUp() {
    lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    payoutService =
        new PayoutService(
            payoutRepository,
            payoutMethodRepository,
            gatewayRegistry,
            eventLogger,
            savedCardRepository,
            notificationService,
            moneyLedgerService,
            clock,
            transactionManager);
  }

  @Test
  void reverse_pendingPayout_fullRefund_marksReversed() {
    PaymentIntent intent = new PaymentIntent();
    Payout payout = Payout.builder().status("PENDING").idempotencyKey("k").build();
    when(payoutRepository.findByTriggeringPaymentIntent(intent)).thenReturn(Optional.of(payout));

    payoutService.reverseOwnerPayoutForRefund(intent, true);

    assertEquals("REVERSED", payout.getStatus());
    verify(payoutRepository).save(payout);
  }

  @Test
  void reverse_alreadyPaidPayout_isNotReversed_flaggedInstead() {
    PaymentIntent intent = new PaymentIntent();
    Payout payout = Payout.builder().status("SUCCESS").idempotencyKey("k").build();
    when(payoutRepository.findByTriggeringPaymentIntent(intent)).thenReturn(Optional.of(payout));

    payoutService.reverseOwnerPayoutForRefund(intent, true);

    // Already dispatched/paid: must NOT silently flip status; left for manual clawback.
    assertEquals("SUCCESS", payout.getStatus());
    verify(payoutRepository, never()).save(any());
  }

  @Test
  void reverse_partialRefund_onPendingPayout_isNotAutoReversed() {
    PaymentIntent intent = new PaymentIntent();
    Payout payout = Payout.builder().status("PENDING").idempotencyKey("k").build();
    when(payoutRepository.findByTriggeringPaymentIntent(intent)).thenReturn(Optional.of(payout));

    payoutService.reverseOwnerPayoutForRefund(intent, false);

    // Partial refund needs an accounting decision — flagged, not auto-reversed.
    assertEquals("PENDING", payout.getStatus());
    verify(payoutRepository, never()).save(any());
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
    when(payoutRepository
            .findByUserAndCurrencyAndStatusInAndReleaseAtAfterOrderByReleaseAtAsc(
                any(), any(), any(), any()))
        .thenReturn(
            List.of(
                Payout.builder()
                    .amount(new BigDecimal("1500.25"))
                    .releaseAt(laterRelease)
                    .build(),
                Payout.builder()
                    .amount(new BigDecimal("499.75"))
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
            .status("PENDING")
            .idempotencyKey("payout-77")
            .releaseAt(LocalDateTime.now(clock).minusSeconds(1))
            .build();
    PayoutMethod method =
        PayoutMethod.builder()
            .user(owner)
            .providerCardToken("tok-owner")
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
            GatewayPayoutResponse.builder()
                .success(true)
                .externalPayoutId("MOCK-OUT-77")
                .build());

    payoutService.processPendingPayouts();

    assertEquals("SUCCESS", payout.getStatus());
    assertEquals("MOCK-OUT-77", payout.getProviderPayoutId());
    assertEquals(null, payout.getLeaseUntil());
    assertNotNull(payout.getProcessedAt());
    verify(paymentGateway, times(1)).payout(any());
  }

  @Test
  void dispatchPayout_activeProcessingLease_isNotSentAgain() {
    Payout payout = Payout.builder().id(88L).status("PROCESSING").build();
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
            .status("PROCESSING")
            .idempotencyKey("payout-89")
            .leaseUntil(LocalDateTime.now(clock).minusMinutes(1))
            .build();
    PayoutMethod method =
        PayoutMethod.builder()
            .user(owner)
            .providerCardToken("tok-owner")
            .status("ACTIVE")
            .build();
    when(payoutRepository.findWithLockById(89L)).thenReturn(Optional.of(payout));
    when(payoutMethodRepository.findByUserAndIsDefaultTrueAndStatus(owner, "ACTIVE"))
        .thenReturn(Optional.of(method));
    when(payoutRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(gatewayRegistry.defaultGateway()).thenReturn(paymentGateway);
    when(paymentGateway.payout(any()))
        .thenReturn(
            GatewayPayoutResponse.builder()
                .success(true)
                .externalPayoutId("MOCK-OUT-89")
                .build());

    payoutService.dispatchPayout(89L);

    assertEquals("SUCCESS", payout.getStatus());
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
            .status("PENDING")
            .idempotencyKey("payout-99")
            .releaseAt(LocalDateTime.now(clock).minusSeconds(1))
            .build();
    PayoutMethod method =
        PayoutMethod.builder()
            .user(owner)
            .providerCardToken("tok-owner")
            .status("ACTIVE")
            .build();
    when(payoutRepository.findWithLockById(99L)).thenReturn(Optional.of(payout));
    when(payoutMethodRepository.findByUserAndIsDefaultTrueAndStatus(owner, "ACTIVE"))
        .thenReturn(Optional.of(method));
    when(payoutRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(gatewayRegistry.defaultGateway()).thenReturn(paymentGateway);
    when(paymentGateway.payout(any()))
        .thenReturn(
            GatewayPayoutResponse.builder()
                .success(true)
                .externalPayoutId("MOCK-OUT-99")
                .build());

    payoutService.dispatchPayout(99L);
    payoutService.dispatchPayout(99L);

    assertEquals("SUCCESS", payout.getStatus());
    verify(paymentGateway, times(1)).payout(any());
  }
}
