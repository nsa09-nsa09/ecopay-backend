package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.payment.gateway.PaymentGatewayRegistry;
import kz.hrms.splitupauth.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

  @Mock private RefundTransactionRepository refundTransactionRepository;
  @Mock private PaymentTransactionRepository paymentTransactionRepository;
  @Mock private DisputeRepository disputeRepository;
  @Mock private AdminActionLogRepository adminActionLogRepository;
  @Mock private PaymentGatewayRegistry gatewayRegistry;
  @Mock private PaymentEventLogger eventLogger;
  @Mock private PayoutService payoutService;
  @Mock private PayoutBlockService payoutBlockService;
  @Mock private NotificationService notificationService;
  @Mock private MoneyLedgerService moneyLedgerService;
  @Mock private PlatformTransactionManager transactionManager;

  private RefundService service;

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
            Clock.systemUTC(),
            transactionManager);
  }

  @Test
  void providerConfirmedClaimRefundAdjustsPayoutThenReleasesClaimBlock() {
    User payer = User.builder().id(1L).build();
    PaymentIntent intent = PaymentIntent.builder().id(2L).user(payer).build();
    PaymentTransaction charge =
        PaymentTransaction.builder()
            .id(3L)
            .paymentIntent(intent)
            .type(PaymentTransactionType.CHARGE)
            .status(PaymentTransactionStatus.SUCCESS)
            .amount(new BigDecimal("1000.00"))
            .currency("KZT")
            .build();
    RefundRequest claim = RefundRequest.builder().id(4L).build();
    RefundTransaction refund =
        RefundTransaction.builder()
            .id(5L)
            .paymentTransaction(charge)
            .refundRequest(claim)
            .status(RefundStatus.PENDING_PROVIDER)
            .amount(new BigDecimal("400.00"))
            .currency("KZT")
            .providerRefundId("provider-refund-5")
            .idempotencyKey("approved-refund-request-4")
            .build();
    when(refundTransactionRepository.findWithLockByProviderRefundId("provider-refund-5"))
        .thenReturn(Optional.of(refund));
    when(refundTransactionRepository.sumSuccessfulRefundAmounts(charge))
        .thenReturn(new BigDecimal("400.00"));

    service.applyRefundWebhook("provider-refund-5", true);

    assertEquals(RefundStatus.SUCCESS, refund.getStatus());
    assertEquals(PaymentTransactionStatus.REFUNDED_PARTIAL, charge.getStatus());
    verify(payoutService).adjustOwnerPayoutForSuccessfulRefund(intent, new BigDecimal("400.00"));
    verify(payoutBlockService)
        .releaseForPaymentIntent(intent, PayoutBlockSourceType.REFUND_REQUEST, 4L);
  }
}
