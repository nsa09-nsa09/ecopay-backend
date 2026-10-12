package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import kz.hrms.splitupauth.dto.CreateRefundRequest;
import kz.hrms.splitupauth.dto.RefundTransactionResponse;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.payment.gateway.PaymentGatewayRegistry;
import kz.hrms.splitupauth.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

  // ---- B8 manual refund (createRefund) ----

  private static User admin() {
    return User.builder().id(99L).role(Role.ADMIN).build();
  }

  private static PaymentTransaction capturedCharge() {
    return PaymentTransaction.builder()
        .id(3L)
        .paymentIntent(PaymentIntent.builder().id(2L).user(User.builder().id(1L).build()).build())
        .type(PaymentTransactionType.CHARGE)
        .status(PaymentTransactionStatus.SUCCESS)
        .amount(new BigDecimal("1000.00"))
        .currency("KZT")
        .build();
  }

  private CreateRefundRequest manualRequest(BigDecimal amount) {
    CreateRefundRequest req = new CreateRefundRequest();
    req.setPaymentTransactionId(3L);
    req.setAmount(amount);
    req.setReason("stuck capture anomaly");
    req.setIdempotencyKey("manual-refund-tx-3");
    return req;
  }

  @Test
  void createRefund_omittedAmountRefundsRemainingBalance() {
    PaymentTransaction charge = capturedCharge();
    when(refundTransactionRepository.findByIdempotencyKey("manual-refund-tx-3"))
        .thenReturn(Optional.empty());
    when(paymentTransactionRepository.findWithLockById(3L)).thenReturn(Optional.of(charge));
    when(refundTransactionRepository.sumActiveRefundAmounts(charge))
        .thenReturn(new BigDecimal("300.00"));
    when(refundTransactionRepository.save(any()))
        .thenAnswer(
            inv -> {
              RefundTransaction r = inv.getArgument(0);
              if (r.getId() == null) r.setId(5L);
              return r;
            });

    RefundTransactionResponse response =
        service.createRefund(admin(), manualRequest(null), mock(HttpServletRequest.class));

    ArgumentCaptor<RefundTransaction> saved = ArgumentCaptor.forClass(RefundTransaction.class);
    verify(refundTransactionRepository).save(saved.capture());
    assertEquals(new BigDecimal("700.00"), saved.getValue().getAmount());
    assertEquals(new BigDecimal("700.00"), response.getAmount());
    verify(adminActionLogRepository).save(any());
  }

  @Test
  void createRefund_repeatedIdempotencyKeyReturnsExistingWithoutSecondRefund() {
    RefundTransaction existing =
        RefundTransaction.builder()
            .id(5L)
            .paymentTransaction(capturedCharge())
            .status(RefundStatus.PENDING)
            .amount(new BigDecimal("700.00"))
            .currency("KZT")
            .idempotencyKey("manual-refund-tx-3")
            .build();
    when(refundTransactionRepository.findByIdempotencyKey("manual-refund-tx-3"))
        .thenReturn(Optional.of(existing));

    RefundTransactionResponse response =
        service.createRefund(
            admin(), manualRequest(new BigDecimal("700.00")), mock(HttpServletRequest.class));

    assertEquals(5L, response.getId());
    verify(refundTransactionRepository, never()).save(any());
    verify(adminActionLogRepository, never()).save(any());
  }

  @Test
  void createRefund_amountAboveRemainingIsRejected() {
    PaymentTransaction charge = capturedCharge();
    when(refundTransactionRepository.findByIdempotencyKey("manual-refund-tx-3"))
        .thenReturn(Optional.empty());
    when(paymentTransactionRepository.findWithLockById(3L)).thenReturn(Optional.of(charge));
    when(refundTransactionRepository.sumActiveRefundAmounts(charge))
        .thenReturn(new BigDecimal("300.00"));

    InvalidRequestException ex =
        assertThrows(
            InvalidRequestException.class,
            () ->
                service.createRefund(
                    admin(),
                    manualRequest(new BigDecimal("800.00")),
                    mock(HttpServletRequest.class)));
    assertEquals("REFUND_AMOUNT_EXCEEDS_BALANCE", ex.getCode());
    verify(refundTransactionRepository, never()).save(any());
  }

  @Test
  void createRefund_nonCapturedTransactionIsRejected() {
    PaymentTransaction charge = capturedCharge();
    charge.setStatus(PaymentTransactionStatus.FAILED);
    when(refundTransactionRepository.findByIdempotencyKey("manual-refund-tx-3"))
        .thenReturn(Optional.empty());
    when(paymentTransactionRepository.findWithLockById(3L)).thenReturn(Optional.of(charge));

    InvalidRequestException ex =
        assertThrows(
            InvalidRequestException.class,
            () ->
                service.createRefund(
                    admin(),
                    manualRequest(new BigDecimal("100.00")),
                    mock(HttpServletRequest.class)));
    assertEquals("REFUND_NOT_CAPTURED", ex.getCode());
    verify(refundTransactionRepository, never()).save(any());
  }
}
