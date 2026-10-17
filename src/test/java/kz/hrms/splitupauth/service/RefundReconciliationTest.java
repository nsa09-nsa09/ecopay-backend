package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentTransaction;
import kz.hrms.splitupauth.entity.PaymentTransactionStatus;
import kz.hrms.splitupauth.entity.PaymentTransactionType;
import kz.hrms.splitupauth.entity.RefundStatus;
import kz.hrms.splitupauth.entity.RefundTransaction;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.payment.gateway.GatewayRequestNotSentException;
import kz.hrms.splitupauth.payment.gateway.GatewayStatusResponse;
import kz.hrms.splitupauth.payment.gateway.PaymentGateway;
import kz.hrms.splitupauth.payment.gateway.PaymentGatewayRegistry;
import kz.hrms.splitupauth.payment.gateway.ProviderPaymentState;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.DisputeRepository;
import kz.hrms.splitupauth.repository.PaymentTransactionRepository;
import kz.hrms.splitupauth.repository.RefundTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/** Refund submission ambiguity and provider-state reconciliation (no refund id from revoke). */
@ExtendWith(MockitoExtension.class)
class RefundReconciliationTest {

  private static final Instant NOW = Instant.parse("2026-10-01T06:00:00Z");
  private static final ZoneId ZONE = ZoneId.of("Asia/Almaty");

  @Mock private RefundTransactionRepository refundTransactionRepository;
  @Mock private PaymentTransactionRepository paymentTransactionRepository;
  @Mock private DisputeRepository disputeRepository;
  @Mock private AdminActionLogRepository adminActionLogRepository;
  @Mock private PaymentGatewayRegistry gatewayRegistry;
  @Mock private PaymentGateway gateway;
  @Mock private PaymentEventLogger eventLogger;
  @Mock private PayoutService payoutService;
  @Mock private PayoutBlockService payoutBlockService;
  @Mock private NotificationService notificationService;
  @Mock private MoneyLedgerService moneyLedgerService;
  @Mock private PlatformTransactionManager transactionManager;

  private RefundService service;
  private PaymentTransaction charge;
  private RefundTransaction refund;

  @BeforeEach
  void setUp() {
    service =
        new RefundService(
            refundTransactionRepository,
            paymentTransactionRepository,
            disputeRepository,
            adminActionLogRepository,
            gatewayRegistry,
            eventLogger,
            payoutService,
            payoutBlockService,
            notificationService,
            moneyLedgerService,
            Clock.fixed(NOW, ZONE),
            transactionManager);
    User payer = User.builder().id(1L).build();
    charge =
        PaymentTransaction.builder()
            .id(3L)
            .paymentIntent(PaymentIntent.builder().id(2L).user(payer).build())
            .type(PaymentTransactionType.CHARGE)
            .status(PaymentTransactionStatus.SUCCESS)
            .externalTransactionId("fp-3")
            .amount(new BigDecimal("1000.00"))
            .currency("KZT")
            .build();
    refund =
        RefundTransaction.builder()
            .id(5L)
            .paymentTransaction(charge)
            .status(RefundStatus.PENDING)
            .amount(new BigDecimal("400.00"))
            .currency("KZT")
            .idempotencyKey("refund-5")
            .build();
    lenient()
        .when(refundTransactionRepository.findWithLockById(5L))
        .thenReturn(Optional.of(refund));
    lenient().when(refundTransactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    lenient().when(gatewayRegistry.defaultGateway()).thenReturn(gateway);
    lenient().when(gateway.providerName()).thenReturn("freedompay");
  }

  private void dispatchableOnce() {
    when(refundTransactionRepository.findDispatchableIds(any(), eq(3), any()))
        .thenReturn(List.of(5L))
        .thenReturn(List.of());
  }

  @Test
  void ambiguousRefundSubmissionIsReconciledNeverResent() {
    dispatchableOnce();
    when(gateway.refund(any())).thenThrow(new IllegalStateException("read timeout"));

    service.processPendingRefundsOnce(5);
    service.processPendingRefundsOnce(5);

    assertEquals(RefundStatus.PENDING_PROVIDER, refund.getStatus());
    assertEquals("SUBMISSION_AMBIGUOUS", refund.getLastErrorCode());
    verify(gateway, times(1)).refund(any());
  }

  @Test
  void notSentRefundIsRetriedSafely() {
    dispatchableOnce();
    when(gateway.refund(any())).thenThrow(new GatewayRequestNotSentException("bulkhead", null));

    service.processPendingRefundsOnce(5);

    assertEquals(RefundStatus.PENDING, refund.getStatus());
    assertEquals(1, refund.getRetryCount());
  }

  @Test
  void partialRefundSettlesOnceProviderShowsTheRefundedAmount() {
    refund.setStatus(RefundStatus.PENDING_PROVIDER);
    when(refundTransactionRepository.findIdsPendingProviderForReconciliation(any(), any()))
        .thenReturn(List.of(5L));
    when(refundTransactionRepository.sumSuccessfulRefundAmounts(charge))
        .thenReturn(BigDecimal.ZERO)
        .thenReturn(new BigDecimal("400.00"));
    when(gateway.getStatus("fp-3"))
        .thenReturn(
            GatewayStatusResponse.builder()
                .status("REVIEW")
                .providerState(ProviderPaymentState.PARTIALLY_REFUNDED)
                .refundedAmount(new BigDecimal("400.00"))
                .build());

    service.reconcilePendingProviderRefunds(10);

    assertEquals(RefundStatus.SUCCESS, refund.getStatus());
    assertEquals(PaymentTransactionStatus.REFUNDED_PARTIAL, charge.getStatus());
    verify(payoutService).adjustOwnerPayoutForSuccessfulRefund(any(), eq(new BigDecimal("400.00")));
  }

  @Test
  void fullRefundSettlesFromRefundedProviderState() {
    refund.setStatus(RefundStatus.PENDING_PROVIDER);
    refund.setAmount(new BigDecimal("1000.00"));
    when(refundTransactionRepository.findIdsPendingProviderForReconciliation(any(), any()))
        .thenReturn(List.of(5L));
    when(refundTransactionRepository.sumSuccessfulRefundAmounts(charge))
        .thenReturn(BigDecimal.ZERO)
        .thenReturn(new BigDecimal("1000.00"));
    when(gateway.getStatus("fp-3"))
        .thenReturn(
            GatewayStatusResponse.builder()
                .status("REVIEW")
                .providerState(ProviderPaymentState.REFUNDED)
                .build());

    service.reconcilePendingProviderRefunds(10);

    assertEquals(RefundStatus.SUCCESS, refund.getStatus());
    assertEquals(PaymentTransactionStatus.REFUNDED_FULL, charge.getStatus());
  }

  @Test
  void refundNotYetVisibleStaysPendingThenGoesToReviewAfter24h() {
    refund.setStatus(RefundStatus.PENDING_PROVIDER);
    refund.setProviderSubmittedAt(LocalDateTime.ofInstant(NOW, ZONE).minusHours(1));
    when(refundTransactionRepository.findIdsPendingProviderForReconciliation(any(), any()))
        .thenReturn(List.of(5L));
    when(refundTransactionRepository.sumSuccessfulRefundAmounts(charge))
        .thenReturn(BigDecimal.ZERO);
    when(gateway.getStatus("fp-3"))
        .thenReturn(
            GatewayStatusResponse.builder()
                .status("SUCCESS")
                .providerState(ProviderPaymentState.CAPTURED)
                .build());

    service.reconcilePendingProviderRefunds(10);
    assertEquals(RefundStatus.PENDING_PROVIDER, refund.getStatus());

    refund.setProviderSubmittedAt(LocalDateTime.ofInstant(NOW, ZONE).minusHours(25));
    service.reconcilePendingProviderRefunds(10);
    assertEquals(RefundStatus.REQUIRES_REVIEW, refund.getStatus());
    verify(gateway, never()).refund(any());
  }

  @Test
  void compensationRefundIsIdempotent() {
    when(refundTransactionRepository.findByIdempotencyKey("compensation-refund-tx-3"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(refund));

    RefundTransaction first = service.createAutomaticCompensationRefund(charge, "ROOM_FULL");
    RefundTransaction second = service.createAutomaticCompensationRefund(charge, "ROOM_FULL");

    assertSame(refund, second);
    assertEquals("compensation-refund-tx-3", first.getIdempotencyKey());
    verify(refundTransactionRepository, times(1)).save(any());
  }
}
