package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.payment.gateway.GatewayWebhookEvent;
import kz.hrms.splitupauth.payment.gateway.PaymentGatewayRegistry;
import kz.hrms.splitupauth.repository.PaymentIntentRepository;
import kz.hrms.splitupauth.repository.PaymentTransactionRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.SavedCardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private PaymentIntentRepository paymentIntentRepository;
    @Mock private PaymentTransactionRepository paymentTransactionRepository;
    @Mock private RoomMemberRepository roomMemberRepository;
    @Mock private SavedCardRepository savedCardRepository;
    @Mock private RoomMemberService roomMemberService;
    @Mock private PaymentGatewayRegistry gatewayRegistry;
    @Mock private SavedCardService savedCardService;
    @Mock private PaymentEventLogger eventLogger;
    @Mock private PayoutService payoutService;
    @Mock private RefundService refundService;
    @Mock private RoomEventLogger roomEventLogger;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                paymentIntentRepository,
                paymentTransactionRepository,
                roomMemberRepository,
                savedCardRepository,
                roomMemberService,
                gatewayRegistry,
                savedCardService,
                eventLogger,
                payoutService,
                refundService,
                roomEventLogger
        );
    }

    private PaymentIntent pendingIntent(BigDecimal amount) {
        User user = User.builder().id(1L).email("m@test.kz").build();
        Room room = Room.builder().id(2L).build();
        RoomMember member = RoomMember.builder().id(3L).user(user).room(room).build();
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

    @Test
    void webhookSuccessWithMatchingAmount_marksPaidAndCreatesPayout() {
        PaymentIntent intent = pendingIntent(new BigDecimal("1822.50"));
        when(paymentIntentRepository.findWithLockById(100L)).thenReturn(Optional.of(intent));
        when(paymentIntentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        GatewayWebhookEvent event = GatewayWebhookEvent.builder()
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
    void webhookSuccessWithMismatchedAmount_isRejectedAsFailed_andDoesNotPay() {
        PaymentIntent intent = pendingIntent(new BigDecimal("1822.50"));
        when(paymentIntentRepository.findWithLockById(100L)).thenReturn(Optional.of(intent));
        when(paymentIntentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        GatewayWebhookEvent event = GatewayWebhookEvent.builder()
                .intentId(100L)
                .resultStatus("SUCCESS")
                .amount(new BigDecimal("1.00")) // tampered / wrong amount
                .currency("KZT")
                .externalPaymentId("EXT-2")
                .providerRequestId("req-2")
                .build();

        paymentService.applyWebhookEvent(event);

        assertEquals(PaymentIntentStatus.FAILED, intent.getStatus());
        assertEquals("AMOUNT_MISMATCH", intent.getFailureCode());
        // Critically: no membership advancement and no payout on a mismatched amount.
        verify(roomMemberService, never()).markMembershipAsPaid(any());
        verify(payoutService, never()).createOwnerPayoutForSuccessfulPayment(any());
        verify(paymentTransactionRepository, never()).save(any());
    }

    @Test
    void payoutWebhook_isRoutedToPayoutService_notTheChargePath() {
        GatewayWebhookEvent event = GatewayWebhookEvent.builder()
                .kind("PAYOUT")
                .resultStatus("SUCCESS")
                .externalPaymentId("MOCK-OUT-9")
                .providerRequestId("req-payout")
                .build();

        paymentService.applyWebhookEvent(event);

        verify(payoutService, times(1)).applyPayoutWebhook("MOCK-OUT-9", true);
        // Not a charge — no intent lookup / membership / charge-payout side effects.
        verify(roomMemberService, never()).markMembershipAsPaid(any());
        verify(payoutService, never()).createOwnerPayoutForSuccessfulPayment(any());
    }

    @Test
    void refundWebhook_isRoutedToRefundService() {
        GatewayWebhookEvent event = GatewayWebhookEvent.builder()
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
    void expireStalePendingIntents_marksThemFailed() {
        PaymentIntent stale = pendingIntent(new BigDecimal("1822.50"));
        stale.setExpiresAt(java.time.LocalDateTime.now().minusMinutes(31));
        when(paymentIntentRepository.findByStatusAndExpiresAtBefore(
                org.mockito.ArgumentMatchers.eq(PaymentIntentStatus.PENDING),
                any())).thenReturn(java.util.List.of(stale));
        when(paymentIntentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        int expired = paymentService.expireStalePendingIntents();

        assertEquals(1, expired);
        assertEquals(PaymentIntentStatus.FAILED, stale.getStatus());
        assertEquals("EXPIRED", stale.getFailureCode());
    }

    @Test
    void webhookForAlreadySuccessfulIntent_isTreatedAsDuplicate_noDoublePay() {
        PaymentIntent intent = pendingIntent(new BigDecimal("1822.50"));
        intent.setStatus(PaymentIntentStatus.SUCCESS); // already terminal
        when(paymentIntentRepository.findWithLockById(100L)).thenReturn(Optional.of(intent));
        when(paymentIntentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        GatewayWebhookEvent event = GatewayWebhookEvent.builder()
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
}
