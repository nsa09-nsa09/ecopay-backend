package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.Payout;
import kz.hrms.splitupauth.payment.gateway.PaymentGatewayRegistry;
import kz.hrms.splitupauth.repository.PayoutMethodRepository;
import kz.hrms.splitupauth.repository.PayoutRepository;
import kz.hrms.splitupauth.repository.SavedCardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PayoutServiceTest {

    @Mock private PayoutRepository payoutRepository;
    @Mock private PayoutMethodRepository payoutMethodRepository;
    @Mock private PaymentGatewayRegistry gatewayRegistry;
    @Mock private PaymentEventLogger eventLogger;
    @Mock private SavedCardRepository savedCardRepository;

    private PayoutService payoutService;

    @BeforeEach
    void setUp() {
        payoutService = new PayoutService(
                payoutRepository, payoutMethodRepository, gatewayRegistry, eventLogger, savedCardRepository);
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
}
