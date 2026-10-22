package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.NotificationType;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentIntentPurpose;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import kz.hrms.splitupauth.entity.PaymentReservation;
import kz.hrms.splitupauth.entity.PaymentReservationStatus;
import kz.hrms.splitupauth.entity.RefundStatus;
import kz.hrms.splitupauth.entity.RefundTransaction;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.payment.gateway.GatewayStatusResponse;
import kz.hrms.splitupauth.payment.gateway.GatewayWebhookEvent;
import kz.hrms.splitupauth.payment.gateway.PaymentGateway;
import kz.hrms.splitupauth.payment.gateway.PaymentGatewayRegistry;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway;
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
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

  @Mock private PaymentIntentRepository paymentIntentRepository;
  @Mock private PaymentReservationRepository paymentReservationRepository;
  @Mock private PaymentTransactionRepository paymentTransactionRepository;
  @Mock private RoomMemberRepository roomMemberRepository;
  @Mock private RoomRepository roomRepository;
  @Mock private SavedCardRepository savedCardRepository;
  @Mock private RoomMemberService roomMemberService;
  @Mock private PaymentGatewayRegistry gatewayRegistry;
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
            new kz.hrms.splitupauth.payment.fiscal.FiscalReceiptBuilder(
                new kz.hrms.splitupauth.payment.fiscal.FiscalProperties()),
            liveMoneyGuard,
            Clock.systemUTC(),
            transactionManager);
  }

  private PaymentIntent pendingIntent(BigDecimal amount) {
    User user = User.builder().id(1L).email("m@test.kz").build();
    Room room = Room.builder().id(2L).maxMembers(2).status(RoomStatus.OPEN).build();
    RoomMember member =
        RoomMember.builder().id(3L).user(user).room(room).status(MemberStatus.APPLIED).build();
    return PaymentIntent.builder()
        .id(100L)
        .idempotencyKey("k-100")
        .roomMember(member)
        .user(user)
        .amount(amount)
        .status(PaymentIntentStatus.PENDING)
        .providerName("mock")
        .build();
  }

  private void stubFreeCapacity(PaymentIntent intent) {
    when(roomMemberRepository.findWithLockById(intent.getRoomMember().getId()))
        .thenReturn(Optional.of(intent.getRoomMember()));
    when(roomRepository.findByIdForUpdate(intent.getRoomMember().getRoom().getId()))
        .thenReturn(Optional.of(intent.getRoomMember().getRoom()));
    when(paymentTransactionRepository.findFirstByPaymentIntentAndTypeAndStatus(any(), any(), any()))
        .thenReturn(Optional.empty());
    when(paymentTransactionRepository.save(any()))
        .thenAnswer(
            i -> {
              var tx = (kz.hrms.splitupauth.entity.PaymentTransaction) i.getArgument(0);
              tx.setId(200L);
              return tx;
            });
  }

  @Test
  void webhookSuccessWithMatchingAmount_marksPaidAndCreatesPayout() {
    PaymentIntent intent = pendingIntent(new BigDecimal("1822.50"));
    when(paymentIntentRepository.findWithLockById(100L)).thenReturn(Optional.of(intent));
    when(paymentIntentRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    stubFreeCapacity(intent);

    GatewayWebhookEvent event =
        GatewayWebhookEvent.builder()
            .intentId(100L)
            .resultStatus("SUCCESS")
            .amount(new BigDecimal("1822.50"))
            .currency("KZT")
            .externalPaymentId("EXT-1")
            .providerRequestId("req-1")
            .build();

    paymentService.applyWebhookEvent(event);

    assertEquals(PaymentIntentStatus.SUCCESS, intent.getStatus());
    verify(roomMemberService, times(1)).markMembershipAsPaid(any());
    verify(payoutService, times(1)).createOwnerPayoutForSuccessfulPayment(intent);
    verify(paymentTransactionRepository, times(1)).save(any());
  }

  @Test
  void webhookSuccessWithMismatchedAmount_isCaptureAnomaly_andDoesNotPay() {
    PaymentIntent intent = pendingIntent(new BigDecimal("1822.50"));
    when(paymentIntentRepository.findWithLockById(100L)).thenReturn(Optional.of(intent));
    when(paymentIntentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    GatewayWebhookEvent event =
        GatewayWebhookEvent.builder()
            .intentId(100L)
            .resultStatus("SUCCESS")
            .amount(new BigDecimal("1.00")) // tampered / wrong amount
            .currency("KZT")
            .externalPaymentId("EXT-2")
            .providerRequestId("req-2")
            .build();

    paymentService.applyWebhookEvent(event);

    assertEquals(PaymentIntentStatus.CAPTURE_ANOMALY, intent.getStatus());
    assertEquals("AMOUNT_MISMATCH", intent.getFailureCode());
    // Critically: no membership advancement and no payout on a mismatched amount.
    verify(roomMemberService, never()).markMembershipAsPaid(any());
    verify(payoutService, never()).createOwnerPayoutForSuccessfulPayment(any());
    verify(paymentTransactionRepository, never()).save(any());
  }

  @Test
  void captureAnomaly_alertsActiveAdminsOnceForTheIntent() {
    PaymentIntent intent = pendingIntent(new BigDecimal("1822.50"));
    when(paymentIntentRepository.findWithLockById(100L)).thenReturn(Optional.of(intent));
    when(paymentIntentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    GatewayWebhookEvent event =
        GatewayWebhookEvent.builder()
            .intentId(100L)
            .resultStatus("SUCCESS")
            .amount(new BigDecimal("1.00"))
            .currency("KZT")
            .externalPaymentId("EXT-2")
            .providerRequestId("req-2")
            .build();

    paymentService.applyWebhookEvent(event);

    // Exactly one admin alert is emitted for this intent; de-duplication across repeats is the
    // responsibility of NotificationService.notifyAdminsOnce (covered by its own test).
    verify(notificationService, times(1))
        .notifyAdminsOnce(
            eq(NotificationType.PAYMENT_CAPTURE_ANOMALY), eq(100L), anyMap(), any(), anyMap());
  }

  @Test
  void webhookWithUnrecognizedStatus_isRecordedInEventLogAndDoesNotBreak() {
    PaymentIntent intent = pendingIntent(new BigDecimal("1000.00"));
    when(paymentIntentRepository.findWithLockById(100L)).thenReturn(Optional.of(intent));
    when(paymentIntentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    GatewayWebhookEvent event =
        GatewayWebhookEvent.builder()
            .intentId(100L)
            .resultStatus("CHARGEBACK") // never emitted today; must not vanish into an INFO line
            .providerRequestId("req-cb")
            .build();

    paymentService.applyWebhookEvent(event);

    // A6: unrecognized status moves PENDING → RECONCILING so the reconciler picks it up.
    assertEquals(PaymentIntentStatus.RECONCILING, intent.getStatus());
    assertEquals("WEBHOOK_UNRECOGNIZED_STATUS", intent.getFailureCode());
    verify(eventLogger)
        .log(
            eq("INTENT"),
            eq(100L),
            eq("WEBHOOK_UNRECOGNIZED_STATUS"),
            eq("PENDING"),
            eq("RECONCILING"),
            any(),
            any(),
            any(),
            anyMap());
    verify(roomMemberService, never()).markMembershipAsPaid(any());
  }

  @Test
  void payoutWebhook_isRoutedToPayoutService_notTheChargePath() {
    GatewayWebhookEvent event =
        GatewayWebhookEvent.builder()
            .kind("PAYOUT")
            .resultStatus("SUCCESS")
            .externalPaymentId("MOCK-OUT-9")
            .providerRequestId("req-payout")
            .build();

    paymentService.applyWebhookEvent(event);

    verify(payoutService, times(1)).applyPayoutWebhook("MOCK-OUT-9", null, true, null);
    // Not a charge — no intent lookup / membership / charge-payout side effects.
    verify(roomMemberService, never()).markMembershipAsPaid(any());
    verify(payoutService, never()).createOwnerPayoutForSuccessfulPayment(any());
  }

  @Test
  void pendingPayoutWebhookDoesNotBecomeFailure() {
    GatewayWebhookEvent event =
        GatewayWebhookEvent.builder()
            .kind("PAYOUT")
            .resultStatus("PENDING")
            .externalPaymentId("MOCK-OUT-9")
            .build();

    paymentService.applyWebhookEvent(event);

    verify(payoutService, never()).applyPayoutWebhook(any(), any(Boolean.class));
  }

  @Test
  void refundWebhook_isRoutedToRefundService() {
    GatewayWebhookEvent event =
        GatewayWebhookEvent.builder()
            .kind("REFUND")
            .resultStatus("FAILED")
            .externalPaymentId("MOCK-REF-7")
            .providerRequestId("req-refund")
            .build();

    paymentService.applyWebhookEvent(event);

    verify(refundService, times(1)).applyRefundWebhook("MOCK-REF-7", false);
    verify(roomMemberService, never()).markMembershipAsPaid(any());
  }

  @Test
  void expireStalePendingIntents_marksThemExpired() {
    PaymentIntent stale = pendingIntent(new BigDecimal("1822.50"));
    stale.setExpiresAt(java.time.LocalDateTime.now().minusMinutes(31));
    when(paymentIntentRepository.findByStatusAndExpiresAtBefore(
            org.mockito.ArgumentMatchers.eq(PaymentIntentStatus.PENDING), any()))
        .thenReturn(java.util.List.of(stale));
    when(paymentIntentRepository.findWithLockById(stale.getId())).thenReturn(Optional.of(stale));
    when(paymentIntentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    int expired = paymentService.expireStalePendingIntents();

    assertEquals(1, expired);
    assertEquals(PaymentIntentStatus.EXPIRED, stale.getStatus());
    assertEquals("EXPIRED", stale.getFailureCode());
  }

  @Test
  void webhookForAlreadySuccessfulIntent_isTreatedAsDuplicate_noDoublePay() {
    PaymentIntent intent = pendingIntent(new BigDecimal("1822.50"));
    intent.setStatus(PaymentIntentStatus.SUCCESS); // already terminal
    when(paymentIntentRepository.findWithLockById(100L)).thenReturn(Optional.of(intent));
    when(paymentIntentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    GatewayWebhookEvent event =
        GatewayWebhookEvent.builder()
            .intentId(100L)
            .resultStatus("SUCCESS")
            .amount(new BigDecimal("1822.50"))
            .currency("KZT")
            .providerRequestId("req-dup")
            .build();

    paymentService.applyWebhookEvent(event);

    verify(roomMemberService, never()).markMembershipAsPaid(any());
    verify(payoutService, never()).createOwnerPayoutForSuccessfulPayment(any());
  }

  @Test
  void webhookForUnknownIntent_isRetryableInsteadOfBeingSilentlyDropped() {
    when(paymentIntentRepository.findWithLockById(404L)).thenReturn(Optional.empty());
    GatewayWebhookEvent event =
        GatewayWebhookEvent.builder()
            .kind("CHARGE")
            .intentId(404L)
            .resultStatus("SUCCESS")
            .providerRequestId("req-late-intent")
            .build();

    FreedomWebhookProcessingException error =
        assertThrows(
            FreedomWebhookProcessingException.class, () -> paymentService.applyWebhookEvent(event));

    assertEquals("INTENT_NOT_FOUND", error.getErrorCode());
    assertTrue(error.isRetryable());
  }

  @Test
  void finalizer_whenRoomIsFull_marksCompensationRequiredWithoutSideEffects() {
    PaymentIntent intent = pendingIntent(new BigDecimal("1822.50"));
    when(paymentIntentRepository.findWithLockById(100L)).thenReturn(Optional.of(intent));
    when(paymentIntentRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(roomMemberRepository.findWithLockById(3L)).thenReturn(Optional.of(intent.getRoomMember()));
    when(roomRepository.findByIdForUpdate(2L))
        .thenReturn(Optional.of(intent.getRoomMember().getRoom()));
    when(roomMemberRepository.countByRoomAndStatusInAndDeletedAtIsNull(any(), any()))
        .thenReturn(1L);
    when(paymentTransactionRepository.findFirstByPaymentIntentAndTypeAndStatus(any(), any(), any()))
        .thenReturn(Optional.empty());
    when(paymentTransactionRepository.save(any()))
        .thenAnswer(
            i -> {
              var tx = (kz.hrms.splitupauth.entity.PaymentTransaction) i.getArgument(0);
              tx.setId(200L);
              return tx;
            });
    when(refundService.createAutomaticCompensationRefund(any(), any()))
        .thenReturn(RefundTransaction.builder().id(300L).status(RefundStatus.PENDING).build());

    PaymentIntent result =
        paymentService.finalizeSuccessfulPayment(
            100L, "EXT-1", "ok", null, null, "req-1", null, "WEBHOOK_SUCCESS");

    assertEquals(PaymentIntentStatus.REFUND_PENDING, result.getStatus());
    assertEquals(true, result.getCompensationRequired());
    verify(roomMemberService, never()).markMembershipAsPaid(any());
    verify(payoutService, never()).createOwnerPayoutForSuccessfulPayment(any());
    verify(paymentTransactionRepository, times(1)).save(any());
    verify(refundService, times(1)).createAutomaticCompensationRefund(any(), any());
  }

  // ── A1: decideOnProviderSuccess — merchant reject/accept decision ──

  private PaymentIntent merchantDecisionIntent(
      BigDecimal amount, MemberStatus memberStatus, RoomStatus roomStatus) {
    User user = User.builder().id(10L).email("m@test.kz").status(UserStatus.ACTIVE).build();
    Room room = Room.builder().id(20L).maxMembers(3).status(roomStatus).build();
    RoomMember member =
        RoomMember.builder().id(30L).user(user).room(room).status(memberStatus).build();
    return PaymentIntent.builder()
        .id(200L)
        .idempotencyKey("k-200")
        .roomMember(member)
        .user(user)
        .amount(amount)
        .status(PaymentIntentStatus.PENDING)
        .providerName("freedompay")
        .build();
  }

  @Test
  void decideOnProviderSuccess_slotAvailable_accepts() {
    PaymentIntent intent =
        merchantDecisionIntent(bd("5000.00"), MemberStatus.APPLIED, RoomStatus.OPEN);
    when(paymentIntentRepository.findById(200L)).thenReturn(Optional.of(intent));
    when(roomMemberRepository.findById(30L)).thenReturn(Optional.of(intent.getRoomMember()));
    PaymentReservation reservation =
        PaymentReservation.builder()
            .id(1L)
            .paymentIntent(intent)
            .status(PaymentReservationStatus.RESERVED)
            .build();
    when(paymentReservationRepository.findByPaymentIntentId(200L))
        .thenReturn(Optional.of(reservation));

    PaymentService.MerchantDecision decision =
        paymentService.decideOnProviderSuccess(200L, bd("5000.00"), "KZT");

    assertTrue(decision.accepted());
  }

  @Test
  void decideOnProviderSuccess_noReservationButCapacityAvailable_accepts() {
    PaymentIntent intent =
        merchantDecisionIntent(bd("5000.00"), MemberStatus.APPLIED, RoomStatus.OPEN);
    when(paymentIntentRepository.findById(200L)).thenReturn(Optional.of(intent));
    when(roomMemberRepository.findById(30L)).thenReturn(Optional.of(intent.getRoomMember()));
    when(paymentReservationRepository.findByPaymentIntentId(200L)).thenReturn(Optional.empty());
    when(roomMemberRepository.countByRoomAndStatusInAndDeletedAtIsNull(
            any(), eq(List.of(MemberStatus.PENDING, MemberStatus.ACTIVE))))
        .thenReturn(1L);

    PaymentService.MerchantDecision decision =
        paymentService.decideOnProviderSuccess(200L, bd("5000.00"), "KZT");

    assertTrue(decision.accepted());
  }

  @Test
  void decideOnProviderSuccess_roomFull_noReservation_rejects() {
    PaymentIntent intent =
        merchantDecisionIntent(bd("5000.00"), MemberStatus.APPLIED, RoomStatus.OPEN);
    when(paymentIntentRepository.findById(200L)).thenReturn(Optional.of(intent));
    when(roomMemberRepository.findById(30L)).thenReturn(Optional.of(intent.getRoomMember()));
    when(paymentReservationRepository.findByPaymentIntentId(200L)).thenReturn(Optional.empty());
    when(roomMemberRepository.countByRoomAndStatusInAndDeletedAtIsNull(
            any(), eq(List.of(MemberStatus.PENDING, MemberStatus.ACTIVE))))
        .thenReturn(3L);

    PaymentService.MerchantDecision decision =
        paymentService.decideOnProviderSuccess(200L, bd("5000.00"), "KZT");

    assertFalse(decision.accepted());
    assertEquals("Место в комнате уже занято, оплата отменена", decision.reasonRu());
  }

  @Test
  void decideOnProviderSuccess_amountMismatch_rejects() {
    PaymentIntent intent =
        merchantDecisionIntent(bd("5000.00"), MemberStatus.APPLIED, RoomStatus.OPEN);
    when(paymentIntentRepository.findById(200L)).thenReturn(Optional.of(intent));

    PaymentService.MerchantDecision decision =
        paymentService.decideOnProviderSuccess(200L, bd("1.00"), "KZT");

    assertFalse(decision.accepted());
    assertEquals("Сумма платежа не совпадает", decision.reasonRu());
  }

  @Test
  void decideOnProviderSuccess_userBanned_rejects() {
    PaymentIntent intent =
        merchantDecisionIntent(bd("5000.00"), MemberStatus.APPLIED, RoomStatus.OPEN);
    intent.getUser().setStatus(UserStatus.BANNED);
    when(paymentIntentRepository.findById(200L)).thenReturn(Optional.of(intent));
    when(roomMemberRepository.findById(30L)).thenReturn(Optional.of(intent.getRoomMember()));

    PaymentService.MerchantDecision decision =
        paymentService.decideOnProviderSuccess(200L, bd("5000.00"), "KZT");

    assertFalse(decision.accepted());
    assertEquals("Аккаунт пользователя заблокирован", decision.reasonRu());
  }

  @Test
  void decideOnProviderSuccess_roomCancelled_rejects() {
    PaymentIntent intent =
        merchantDecisionIntent(bd("5000.00"), MemberStatus.APPLIED, RoomStatus.CANCELLED);
    when(paymentIntentRepository.findById(200L)).thenReturn(Optional.of(intent));
    when(roomMemberRepository.findById(30L)).thenReturn(Optional.of(intent.getRoomMember()));

    PaymentService.MerchantDecision decision =
        paymentService.decideOnProviderSuccess(200L, bd("5000.00"), "KZT");

    assertFalse(decision.accepted());
    assertEquals("Комната отменена", decision.reasonRu());
  }

  @Test
  void decideOnProviderSuccess_memberNotApplied_rejects() {
    PaymentIntent intent =
        merchantDecisionIntent(
            bd("5000.00"), MemberStatus.CANCELLED_BEFORE_PAYMENT, RoomStatus.OPEN);
    when(paymentIntentRepository.findById(200L)).thenReturn(Optional.of(intent));
    when(roomMemberRepository.findById(30L)).thenReturn(Optional.of(intent.getRoomMember()));

    PaymentService.MerchantDecision decision =
        paymentService.decideOnProviderSuccess(200L, bd("5000.00"), "KZT");

    assertFalse(decision.accepted());
    assertEquals("Заявка на вступление отменена", decision.reasonRu());
  }

  @Test
  void decideOnProviderSuccess_renewalActiveUser_accepts() {
    PaymentIntent intent =
        merchantDecisionIntent(bd("5000.00"), MemberStatus.ACTIVE, RoomStatus.ACTIVE);
    intent.setPurpose(PaymentIntentPurpose.RENEWAL);
    when(paymentIntentRepository.findById(200L)).thenReturn(Optional.of(intent));
    when(roomMemberRepository.findById(30L)).thenReturn(Optional.of(intent.getRoomMember()));
    when(paymentReservationRepository.findByPaymentIntentId(200L)).thenReturn(Optional.empty());
    when(roomMemberRepository.countByRoomAndStatusInAndDeletedAtIsNull(
            any(), eq(List.of(MemberStatus.PENDING, MemberStatus.ACTIVE))))
        .thenReturn(1L);

    PaymentService.MerchantDecision decision =
        paymentService.decideOnProviderSuccess(200L, bd("5000.00"), "KZT");

    assertTrue(decision.accepted());
  }

  @Test
  void decideOnProviderSuccess_alreadySuccessful_accepts() {
    PaymentIntent intent =
        merchantDecisionIntent(bd("5000.00"), MemberStatus.APPLIED, RoomStatus.OPEN);
    intent.setStatus(PaymentIntentStatus.SUCCESS);
    when(paymentIntentRepository.findById(200L)).thenReturn(Optional.of(intent));

    PaymentService.MerchantDecision decision =
        paymentService.decideOnProviderSuccess(200L, bd("5000.00"), "KZT");

    assertTrue(decision.accepted());
  }

  @Test
  void decideOnProviderSuccess_intentNotFound_rejects() {
    when(paymentIntentRepository.findById(999L)).thenReturn(Optional.empty());

    PaymentService.MerchantDecision decision =
        paymentService.decideOnProviderSuccess(999L, bd("5000.00"), "KZT");

    assertFalse(decision.accepted());
    assertEquals("Платёж не найден", decision.reasonRu());
  }

  // ── A1: applyMerchantRejection — state transition ──

  @Test
  void applyMerchantRejection_marksFailed_releasesReservation() {
    PaymentIntent intent =
        merchantDecisionIntent(bd("5000.00"), MemberStatus.APPLIED, RoomStatus.OPEN);
    when(paymentIntentRepository.findWithLockById(200L)).thenReturn(Optional.of(intent));
    when(paymentIntentRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    PaymentReservation reservation =
        PaymentReservation.builder()
            .id(1L)
            .paymentIntent(intent)
            .status(PaymentReservationStatus.RESERVED)
            .build();
    when(paymentReservationRepository.findWithLockByPaymentIntentId(200L))
        .thenReturn(Optional.of(reservation));
    when(paymentReservationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    paymentService.applyMerchantRejection(200L, "Место в комнате уже занято, оплата отменена");

    assertEquals(PaymentIntentStatus.FAILED, intent.getStatus());
    assertEquals("MERCHANT_REJECTED", intent.getFailureCode());
    assertEquals(PaymentReservationStatus.RELEASED, reservation.getStatus());
    verify(eventLogger)
        .log(
            eq("INTENT"),
            eq(200L),
            eq("MERCHANT_REJECTED"),
            eq("PENDING"),
            eq("FAILED"),
            any(),
            any(),
            any(),
            anyMap());
  }

  @Test
  void applyMerchantRejection_alreadyFailed_isNoop() {
    PaymentIntent intent =
        merchantDecisionIntent(bd("5000.00"), MemberStatus.APPLIED, RoomStatus.OPEN);
    intent.setStatus(PaymentIntentStatus.FAILED);
    when(paymentIntentRepository.findWithLockById(200L)).thenReturn(Optional.of(intent));

    paymentService.applyMerchantRejection(200L, "some reason");

    assertEquals(PaymentIntentStatus.FAILED, intent.getStatus());
    verify(paymentIntentRepository, never()).save(any());
  }

  // ── A6: Unknown status escalation ──

  @Test
  void webhookUnrecognizedStatus_movesPendingToReconciling() {
    PaymentIntent intent = pendingIntent(new BigDecimal("5000.00"));
    when(paymentIntentRepository.findWithLockById(100L)).thenReturn(Optional.of(intent));
    when(paymentIntentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    GatewayWebhookEvent event =
        GatewayWebhookEvent.builder()
            .intentId(100L)
            .resultStatus("CHARGEBACK")
            .externalPaymentId("EXT-1")
            .providerRequestId("req-1")
            .build();

    paymentService.applyWebhookEvent(event);

    assertEquals(PaymentIntentStatus.RECONCILING, intent.getStatus());
    assertEquals("WEBHOOK_UNRECOGNIZED_STATUS", intent.getFailureCode());
    verify(eventLogger)
        .log(
            eq("INTENT"),
            eq(100L),
            eq("WEBHOOK_UNRECOGNIZED_STATUS"),
            eq("PENDING"),
            eq("RECONCILING"),
            any(),
            any(),
            any(),
            anyMap());
  }

  @Test
  void webhookUnrecognizedStatus_alreadyReconcilingStaysReconciling() {
    PaymentIntent intent = pendingIntent(new BigDecimal("5000.00"));
    intent.setStatus(PaymentIntentStatus.RECONCILING);
    when(paymentIntentRepository.findWithLockById(100L)).thenReturn(Optional.of(intent));
    when(paymentIntentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    GatewayWebhookEvent event =
        GatewayWebhookEvent.builder()
            .intentId(100L)
            .resultStatus("CHARGEBACK")
            .externalPaymentId("EXT-1")
            .providerRequestId("req-1")
            .build();

    paymentService.applyWebhookEvent(event);

    assertEquals(PaymentIntentStatus.RECONCILING, intent.getStatus());
  }

  @Test
  void reconcileEscalatesToReviewAfterThresholdAttempts_providerPending() {
    when(transactionManager.getTransaction(any()))
        .thenReturn(org.mockito.Mockito.mock(TransactionStatus.class));

    PaymentIntent intent = pendingIntent(new BigDecimal("5000.00"));
    intent.setProviderName(FreedomPayGateway.PROVIDER_NAME);
    intent.setReconcileAttempts(10);
    intent.setStatus(PaymentIntentStatus.RECONCILING);

    when(paymentIntentRepository.findIdsForProviderReconciliation(
            any(), any(), any(), any(), eq(50), any()))
        .thenReturn(List.of(100L));
    when(paymentIntentRepository.findById(100L)).thenReturn(Optional.of(intent));
    when(paymentIntentRepository.findWithLockById(100L)).thenReturn(Optional.of(intent));
    when(paymentIntentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    PaymentGateway mockGateway = org.mockito.Mockito.mock(PaymentGateway.class);
    when(gatewayRegistry.resolve(FreedomPayGateway.PROVIDER_NAME)).thenReturn(mockGateway);
    when(mockGateway.getStatusByOrderId("100"))
        .thenReturn(
            GatewayStatusResponse.builder().status("PENDING").externalPaymentId("EXT-1").build());

    paymentService.reconcileOpenIntents(10, 0);

    assertEquals(PaymentIntentStatus.REQUIRES_REVIEW, intent.getStatus());
    assertEquals("PROVIDER_STATUS_UNRESOLVED", intent.getFailureCode());
    assertTrue(intent.getReviewRequired());
    verify(notificationService)
        .notifyAdminsOnce(
            eq(NotificationType.PAYMENT_REQUIRES_REVIEW), eq(100L), anyMap(), any(), anyMap());
  }

  @Test
  void reconcileEscalatesToReviewAfterThresholdAttempts_gatewayUnreachable() {
    when(transactionManager.getTransaction(any()))
        .thenReturn(org.mockito.Mockito.mock(TransactionStatus.class));

    PaymentIntent intent = pendingIntent(new BigDecimal("5000.00"));
    intent.setProviderName(FreedomPayGateway.PROVIDER_NAME);
    intent.setReconcileAttempts(10);
    intent.setStatus(PaymentIntentStatus.RECONCILING);

    when(paymentIntentRepository.findIdsForProviderReconciliation(
            any(), any(), any(), any(), eq(50), any()))
        .thenReturn(List.of(100L));
    when(paymentIntentRepository.findById(100L)).thenReturn(Optional.of(intent));
    when(paymentIntentRepository.findWithLockById(100L)).thenReturn(Optional.of(intent));
    when(paymentIntentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    PaymentGateway mockGateway = org.mockito.Mockito.mock(PaymentGateway.class);
    when(gatewayRegistry.resolve(FreedomPayGateway.PROVIDER_NAME)).thenReturn(mockGateway);
    when(mockGateway.getStatusByOrderId("100"))
        .thenThrow(new RuntimeException("Connection refused"));

    paymentService.reconcileOpenIntents(10, 0);

    assertEquals(PaymentIntentStatus.REQUIRES_REVIEW, intent.getStatus());
    assertEquals("GATEWAY_UNREACHABLE_ESCALATED", intent.getFailureCode());
    assertTrue(intent.getReviewRequired());
    verify(notificationService)
        .notifyAdminsOnce(
            eq(NotificationType.PAYMENT_REQUIRES_REVIEW), eq(100L), anyMap(), any(), anyMap());
  }

  @Test
  void reconcileBelowThreshold_staysReconciling_noEscalation() {
    when(transactionManager.getTransaction(any()))
        .thenReturn(org.mockito.Mockito.mock(TransactionStatus.class));

    PaymentIntent intent = pendingIntent(new BigDecimal("5000.00"));
    intent.setProviderName(FreedomPayGateway.PROVIDER_NAME);
    intent.setReconcileAttempts(5);
    intent.setStatus(PaymentIntentStatus.PENDING);

    when(paymentIntentRepository.findIdsForProviderReconciliation(
            any(), any(), any(), any(), eq(50), any()))
        .thenReturn(List.of(100L));
    when(paymentIntentRepository.findById(100L)).thenReturn(Optional.of(intent));
    when(paymentIntentRepository.findWithLockById(100L)).thenReturn(Optional.of(intent));
    when(paymentIntentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    PaymentGateway mockGateway = org.mockito.Mockito.mock(PaymentGateway.class);
    when(gatewayRegistry.resolve(FreedomPayGateway.PROVIDER_NAME)).thenReturn(mockGateway);
    when(mockGateway.getStatusByOrderId("100"))
        .thenReturn(GatewayStatusResponse.builder().status("PENDING").build());

    paymentService.reconcileOpenIntents(10, 0);

    // Should stay in PENDING (not escalated) — reconcileAttempts (now 6) is below threshold of 10
    assertEquals(PaymentIntentStatus.PENDING, intent.getStatus());
    verify(notificationService, never())
        .notifyAdminsOnce(
            eq(NotificationType.PAYMENT_REQUIRES_REVIEW), any(), anyMap(), any(), anyMap());
  }

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }
}
