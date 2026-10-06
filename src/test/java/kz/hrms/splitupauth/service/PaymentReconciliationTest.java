package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import kz.hrms.splitupauth.entity.PaymentTransaction;
import kz.hrms.splitupauth.entity.RefundStatus;
import kz.hrms.splitupauth.entity.RefundTransaction;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.payment.gateway.GatewayStatusResponse;
import kz.hrms.splitupauth.payment.gateway.GatewayWebhookEvent;
import kz.hrms.splitupauth.payment.gateway.PaymentGateway;
import kz.hrms.splitupauth.payment.gateway.PaymentGatewayRegistry;
import kz.hrms.splitupauth.payment.gateway.ProviderPaymentState;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPaySignatureException;
import kz.hrms.splitupauth.repository.PaymentIntentRepository;
import kz.hrms.splitupauth.repository.PaymentReservationRepository;
import kz.hrms.splitupauth.repository.PaymentTransactionRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.SavedCardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * P0 money-path scenarios around the provider result: browser return vs. callback ordering,
 * ambiguous initiation, pending status, invalid signatures, amount/currency mismatch, two-step
 * authorization and late success for a membership that can no longer be paid.
 */
@ExtendWith(MockitoExtension.class)
class PaymentReconciliationTest {

  @Mock private PaymentIntentRepository paymentIntentRepository;
  @Mock private PaymentReservationRepository paymentReservationRepository;
  @Mock private PaymentTransactionRepository paymentTransactionRepository;
  @Mock private RoomMemberRepository roomMemberRepository;
  @Mock private RoomRepository roomRepository;
  @Mock private SavedCardRepository savedCardRepository;
  @Mock private RoomMemberService roomMemberService;
  @Mock private PaymentGatewayRegistry gatewayRegistry;
  @Mock private PaymentGateway gateway;
  @Mock private SavedCardService savedCardService;
  @Mock private PaymentEventLogger eventLogger;
  @Mock private PayoutService payoutService;
  @Mock private RefundService refundService;
  @Mock private RoomEventLogger roomEventLogger;
  @Mock private NotificationService notificationService;
  @Mock private CommissionCalculator commissionCalculator;
  @Mock private MoneyLedgerService moneyLedgerService;
  @Mock private LiveMoneyGuard liveMoneyGuard;
  @Mock private PlatformTransactionManager transactionManager;

  private PaymentService paymentService;
  private PaymentIntent intent;
  private final User payer = User.builder().id(1L).email("m@test.kz").build();

  @BeforeEach
  void setUp() {
    paymentService =
        new PaymentService(
            paymentIntentRepository,
            paymentReservationRepository,
            paymentTransactionRepository,
            roomMemberRepository,
            roomRepository,
            savedCardRepository,
            roomMemberService,
            gatewayRegistry,
            savedCardService,
            eventLogger,
            payoutService,
            refundService,
            roomEventLogger,
            notificationService,
            commissionCalculator,
            moneyLedgerService,
            liveMoneyGuard,
            Clock.systemUTC(),
            transactionManager);

    Room room = Room.builder().id(2L).maxMembers(2).status(RoomStatus.OPEN).build();
    RoomMember member =
        RoomMember.builder().id(3L).user(payer).room(room).status(MemberStatus.APPLIED).build();
    intent =
        PaymentIntent.builder()
            .id(100L)
            .idempotencyKey("k-100")
            .roomMember(member)
            .user(payer)
            .amount(new BigDecimal("2322.50"))
            .status(PaymentIntentStatus.PENDING)
            .providerName("freedompay")
            .externalPaymentId("fp-100")
            .build();
    lenient().when(paymentIntentRepository.findById(100L)).thenReturn(Optional.of(intent));
    lenient().when(paymentIntentRepository.findWithLockById(100L)).thenReturn(Optional.of(intent));
    lenient().when(paymentIntentRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    lenient().when(gatewayRegistry.resolve("freedompay")).thenReturn(gateway);
  }

  private void stubSeatAndTransaction() {
    when(roomMemberRepository.findWithLockById(3L)).thenReturn(Optional.of(intent.getRoomMember()));
    when(roomRepository.findByIdForUpdate(2L))
        .thenReturn(Optional.of(intent.getRoomMember().getRoom()));
    lenient()
        .when(
            paymentTransactionRepository.findFirstByPaymentIntentAndTypeAndStatus(
                any(), any(), any()))
        .thenReturn(Optional.empty());
    lenient()
        .when(paymentTransactionRepository.save(any()))
        .thenAnswer(
            i -> {
              PaymentTransaction tx = i.getArgument(0);
              tx.setId(200L);
              return tx;
            });
  }

  private static GatewayStatusResponse captured(String amount, String currency) {
    return GatewayStatusResponse.builder()
        .externalPaymentId("fp-100")
        .status("SUCCESS")
        .providerState(ProviderPaymentState.CAPTURED)
        .amount(new BigDecimal(amount))
        .currency(currency)
        .build();
  }

  @Test
  void callbackBeforeBrowserReturn_returnIsAReadOnlyNoOp() {
    intent.setStatus(PaymentIntentStatus.SUCCESS);

    var response = paymentService.confirmPaymentSuccess(100L, payer, null);

    assertEquals(PaymentIntentStatus.SUCCESS, response.getStatus());
    verify(gateway, never()).getStatus(anyString());
    verify(payoutService, never()).createOwnerPayoutForSuccessfulPayment(any());
  }

  @Test
  void browserReturnBeforeCallback_settlesFromProviderStatus_thenCallbackIsDuplicate() {
    stubSeatAndTransaction();
    when(gateway.getStatus("fp-100")).thenReturn(captured("2322.50", "KZT"));

    var response = paymentService.confirmPaymentSuccess(100L, payer, null);
    assertEquals(PaymentIntentStatus.SUCCESS, response.getStatus());

    paymentService.applyWebhookEvent(
        GatewayWebhookEvent.builder()
            .kind("CHARGE")
            .intentId(100L)
            .resultStatus("SUCCESS")
            .amount(new BigDecimal("2322.50"))
            .currency("KZT")
            .providerRequestId("late-callback")
            .build());

    verify(payoutService, times(1)).createOwnerPayoutForSuccessfulPayment(any());
    verify(roomMemberService, times(1)).markMembershipAsPaid(any());
  }

  @Test
  void browserReturnWhileProviderStillPending_leavesIntentOpen() {
    when(gateway.getStatus("fp-100"))
        .thenReturn(
            GatewayStatusResponse.builder()
                .status("PENDING")
                .providerState(ProviderPaymentState.PENDING)
                .build());

    var response = paymentService.confirmPaymentSuccess(100L, payer, null);

    assertEquals(PaymentIntentStatus.PENDING, response.getStatus());
    verify(payoutService, never()).createOwnerPayoutForSuccessfulPayment(any());
  }

  @Test
  void invalidProviderResponseSignature_neverActivates_marksReconciling() {
    when(gateway.getStatus("fp-100")).thenThrow(new FreedomPaySignatureException("bad sig"));

    var response = paymentService.confirmPaymentSuccess(100L, payer, null);

    assertEquals(PaymentIntentStatus.RECONCILING, response.getStatus());
    verify(roomMemberService, never()).markMembershipAsPaid(any());
    verify(payoutService, never()).createOwnerPayoutForSuccessfulPayment(any());
  }

  @Test
  void providerTimeoutAfterAcceptingPayment_isRecoveredByOrderIdLookup() {
    intent.setStatus(PaymentIntentStatus.UNKNOWN);
    intent.setExternalPaymentId(null);
    stubSeatAndTransaction();
    when(paymentIntentRepository.findIdsForProviderReconciliation(
            any(), anyString(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
        .thenReturn(java.util.List.of(100L));
    when(gateway.getStatusByOrderId("100")).thenReturn(captured("2322.50", "KZT"));

    int queried = paymentService.reconcileOpenIntents(5, 0);

    assertEquals(1, queried);
    assertEquals(PaymentIntentStatus.SUCCESS, intent.getStatus());
    assertEquals("fp-100", intent.getExternalPaymentId());
    assertEquals(1, intent.getReconcileAttempts());
    verify(payoutService, times(1)).createOwnerPayoutForSuccessfulPayment(any());
  }

  @Test
  void amountMismatchOnBrowserReturn_isCaptureAnomalyNotSuccess() {
    when(gateway.getStatus("fp-100")).thenReturn(captured("1.00", "KZT"));

    var response = paymentService.confirmPaymentSuccess(100L, payer, null);

    assertEquals(PaymentIntentStatus.CAPTURE_ANOMALY, response.getStatus());
    assertEquals("AMOUNT_MISMATCH", intent.getReviewReason());
    verify(payoutService, never()).createOwnerPayoutForSuccessfulPayment(any());
  }

  @Test
  void currencyMismatchOnBrowserReturn_isCaptureAnomalyNotSuccess() {
    when(gateway.getStatus("fp-100")).thenReturn(captured("2322.50", "USD"));

    var response = paymentService.confirmPaymentSuccess(100L, payer, null);

    assertEquals(PaymentIntentStatus.CAPTURE_ANOMALY, response.getStatus());
    assertEquals("CURRENCY_MISMATCH", intent.getReviewReason());
    verify(roomMemberService, never()).markMembershipAsPaid(any());
  }

  @Test
  void currencyMismatchCallback_isCaptureAnomalyNotSuccess() {
    paymentService.applyWebhookEvent(
        GatewayWebhookEvent.builder()
            .kind("CHARGE")
            .intentId(100L)
            .resultStatus("SUCCESS")
            .amount(new BigDecimal("2322.50"))
            .currency("USD")
            .build());

    assertEquals(PaymentIntentStatus.CAPTURE_ANOMALY, intent.getStatus());
    verify(payoutService, never()).createOwnerPayoutForSuccessfulPayment(any());
  }

  @Test
  void twoStepAuthorizationIsNotCapturedMoney() {
    when(gateway.getStatus("fp-100"))
        .thenReturn(
            GatewayStatusResponse.builder()
                .status("PENDING")
                .providerState(ProviderPaymentState.AUTHORIZED)
                .build());

    var response = paymentService.confirmPaymentSuccess(100L, payer, null);

    assertEquals(PaymentIntentStatus.RECONCILING, response.getStatus());
    assertTrue(intent.getReviewRequired());
    verify(payoutService, never()).createOwnerPayoutForSuccessfulPayment(any());
  }

  @Test
  void authorizedCallbackWithPgCapturedZeroDoesNotActivate() {
    paymentService.applyWebhookEvent(
        GatewayWebhookEvent.builder()
            .kind("CHARGE")
            .intentId(100L)
            .resultStatus("SUCCESS")
            .captured(false)
            .amount(new BigDecimal("2322.50"))
            .currency("KZT")
            .build());

    assertEquals(PaymentIntentStatus.RECONCILING, intent.getStatus());
    verify(roomMemberService, never()).markMembershipAsPaid(any());
    verify(payoutService, never()).createOwnerPayoutForSuccessfulPayment(any());
  }

  @Test
  void providerRefundedBeforeEcoPayCapturedIt_goesToReview() {
    when(gateway.getStatus("fp-100"))
        .thenReturn(
            GatewayStatusResponse.builder()
                .status("REVIEW")
                .providerState(ProviderPaymentState.REFUNDED)
                .build());

    paymentService.confirmPaymentSuccess(100L, payer, null);

    assertTrue(intent.getReviewRequired());
    verify(payoutService, never()).createOwnerPayoutForSuccessfulPayment(any());
  }

  @Test
  void lateSuccessForRejectedMembership_triggersCompensationInsteadOfDeadLetter() {
    intent.getRoomMember().setStatus(MemberStatus.REJECTED);
    stubSeatAndTransaction();
    when(refundService.createAutomaticCompensationRefund(any(), anyString()))
        .thenReturn(RefundTransaction.builder().id(9L).status(RefundStatus.PENDING).build());

    paymentService.applyWebhookEvent(
        GatewayWebhookEvent.builder()
            .kind("CHARGE")
            .intentId(100L)
            .resultStatus("SUCCESS")
            .amount(new BigDecimal("2322.50"))
            .currency("KZT")
            .build());

    assertEquals(PaymentIntentStatus.REFUND_PENDING, intent.getStatus());
    assertTrue(intent.getCompensationRequired());
    verify(roomMemberService, never()).markMembershipAsPaid(any());
    verify(payoutService, never()).createOwnerPayoutForSuccessfulPayment(any());
  }

  @Test
  void expiryNeverOverwritesAnIntentFinalizedConcurrently() {
    PaymentIntent scanned =
        PaymentIntent.builder()
            .id(100L)
            .status(PaymentIntentStatus.PENDING)
            .expiresAt(LocalDateTime.now().minusMinutes(1))
            .build();
    intent.setStatus(PaymentIntentStatus.SUCCESS); // finalized after the scan
    intent.setExpiresAt(LocalDateTime.now().minusMinutes(1));
    when(paymentIntentRepository.findByStatusAndExpiresAtBefore(
            org.mockito.ArgumentMatchers.eq(PaymentIntentStatus.PENDING), any()))
        .thenReturn(java.util.List.of(scanned));

    int expired = paymentService.expireStalePendingIntents();

    assertEquals(0, expired);
    assertEquals(PaymentIntentStatus.SUCCESS, intent.getStatus());
  }
}
