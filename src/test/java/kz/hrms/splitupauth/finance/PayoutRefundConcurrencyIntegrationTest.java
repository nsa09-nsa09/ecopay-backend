package kz.hrms.splitupauth.finance;

import kz.hrms.splitupauth.dto.CreateRefundRequest;
import kz.hrms.splitupauth.dto.PaymentIntentResponse;
import kz.hrms.splitupauth.dto.RefundTransactionResponse;
import kz.hrms.splitupauth.dto.RoomResponse;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.payment.gateway.GatewayPayoutRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayWebhookEvent;
import kz.hrms.splitupauth.service.PayoutService;
import kz.hrms.splitupauth.service.RefundService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/**
 * Owner payouts and member refunds racing each other, provider timeouts and
 * crashes between "provider accepted" and "DB updated".
 */
class PayoutRefundConcurrencyIntegrationTest extends FinancialTestSupport {

    @Autowired PayoutService payoutService;
    @Autowired RefundService refundService;

    /** Error (not Exception) so no application catch block can swallow it: models a process crash. */
    static class SimulatedCrash extends Error {
        SimulatedCrash() { super("simulated process crash after provider accepted the payout"); }
    }

    private record Paid(User owner, User member, RoomResponse room, Long intentId, Long chargeTxId, Long payoutId) {}

    /** A member pays 1000.00 KZT through the synchronous mock gateway -> PENDING owner payout of 920.00. */
    private Paid paidSeat(boolean ownerHasPayoutMethod) {
        User owner = registerVerified("Payout owner");
        if (ownerHasPayoutMethod) givePayoutMethod(owner);
        RoomResponse room = createRoom(owner, 3);
        User member = registerVerified("Payout member");
        Long memberId = join(room.getId(), member);
        PaymentIntentResponse intent = pay(memberId, member, "paid-" + memberId);
        Long txId = jdbc.queryForObject("select id from payment_transactions where payment_intent_id = ? and type = 'CHARGE'",
                Long.class, intent.getId());
        return new Paid(owner, member, room, intent.getId(), txId, payoutIdForRoom(room.getId()));
    }

    private long providerPayoutCalls(Long payoutId) {
        return mockingDetails(gateway).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("payout"))
                .filter(inv -> ((GatewayPayoutRequest) inv.getArgument(0)).getPayoutId().equals(payoutId))
                .count();
    }

    private RefundTransactionResponse userRefund(Paid p, String amount, String key) {
        CreateRefundRequest r = new CreateRefundRequest();
        r.setPaymentTransactionId(p.chargeTxId());
        r.setAmount(new BigDecimal(amount));
        r.setReason("test refund");
        r.setIdempotencyKey(key);
        return refundService.requestRefund(p.member(), r);
    }

    private BigDecimal payoutAmount(Long payoutId) {
        return jdbc.queryForObject("select amount from payouts where id = ?", BigDecimal.class, payoutId);
    }

    private boolean clawbackFlagged(Long payoutId) {
        return count("select count(*) from payment_event_log where entity_type = 'PAYOUT' and entity_id = ? "
                + "and event_type = 'CLAWBACK_REQUIRED'", payoutId) > 0;
    }

    // ------------------------------------------------------------------ payouts

    @Test
    void tenConcurrentDispatchers_payOutExactlyOnce() throws Exception {
        Paid p = paidSeat(true);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) tasks.add(() -> { payoutService.dispatchPayout(p.payoutId()); return null; });
        runConcurrently(tasks);

        assertEquals(1, providerPayoutCalls(p.payoutId()), "no double payout");
        assertEquals("SUCCESS", payoutStatus(p.payoutId()));
    }

    /**
     * The provider accepts the payout but the HTTP response times out. Before
     * the fix the payout went back to PENDING and the next dispatcher run paid
     * the owner a second time.
     */
    @Test
    void timeoutAfterProviderAcceptance_isUnknown_neverRedispatched_andWebhookSettlesIt() {
        Paid p = paidSeat(true);
        doAnswer(inv -> {
            inv.callRealMethod();
            throw new RuntimeException(new SocketTimeoutException("Read timed out"));
        }).when(gateway).payout(argThat(r -> r.getPayoutId().equals(p.payoutId())));

        payoutService.dispatchPayout(p.payoutId());
        payoutService.dispatchPayout(p.payoutId());
        payoutService.processPendingPayouts();

        assertEquals(1, providerPayoutCalls(p.payoutId()), "a lost response must not trigger a second payout");
        assertEquals("UNKNOWN", payoutStatus(p.payoutId()));

        // Provider callback carries our immutable reference (pg_order_id = payout id).
        paymentService.applyWebhookEvent(GatewayWebhookEvent.builder()
                .kind("PAYOUT").resultStatus("SUCCESS").externalPaymentId("FP-OUT-" + p.payoutId())
                .rawParams(Map.of("pg_order_id", String.valueOf(p.payoutId())))
                .providerRequestId(UUID.randomUUID().toString()).build());
        assertEquals("SUCCESS", payoutStatus(p.payoutId()));
    }

    /**
     * Provider succeeded, then the process died before the result was written.
     * Before the fix the whole transaction rolled back to PENDING and the
     * retrying dispatcher paid again.
     */
    @Test
    void crashAfterProviderAcceptance_dispatcherRetry_doesNotPayTwice() {
        Paid p = paidSeat(true);
        doAnswer(inv -> {
            inv.callRealMethod();
            throw new SimulatedCrash();
        }).when(gateway).payout(argThat(r -> r.getPayoutId().equals(p.payoutId())));

        assertThrows(SimulatedCrash.class, () -> payoutService.dispatchPayout(p.payoutId()));
        payoutService.processPendingPayouts();
        payoutService.dispatchPayout(p.payoutId());

        assertEquals(1, providerPayoutCalls(p.payoutId()), "retry after crash must not re-send the payout");
        assertEquals("PROCESSING", payoutStatus(p.payoutId()));

        // The reconciler parks an interrupted claim as UNKNOWN (still never re-sent).
        jdbc.update("update payouts set updated_at = now() - interval '1 hour' where id = ?", p.payoutId());
        payoutService.reconcileStuckPayouts();
        assertEquals("UNKNOWN", payoutStatus(p.payoutId()));
        payoutService.processPendingPayouts();
        assertEquals(1, providerPayoutCalls(p.payoutId()));

        paymentService.applyWebhookEvent(GatewayWebhookEvent.builder()
                .kind("PAYOUT").resultStatus("SUCCESS").externalPaymentId("FP-OUT-" + p.payoutId())
                .rawParams(Map.of("pg_order_id", String.valueOf(p.payoutId())))
                .providerRequestId(UUID.randomUUID().toString()).build());
        assertEquals("SUCCESS", payoutStatus(p.payoutId()));
    }

    /**
     * Refund and payout dispatch hit the same PENDING payout at the same time.
     * Exactly one may win: either the payout is reversed and never sent, or it
     * is sent and the refund is flagged for clawback. Before the fix the
     * dispatcher sent the money AND the refund silently reversed the payable.
     */
    @Test
    void refundRacingPayoutDispatch_neverPaysReversedPayable() throws Exception {
        Paid p = paidSeat(true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection slowWriter = lockRow("payouts", p.payoutId())) {
            Future<Outcome<Void>> dispatch = startAsync(pool, () -> { payoutService.dispatchPayout(p.payoutId()); return null; });
            Future<Outcome<RefundTransactionResponse>> refund = startAsync(pool, () -> userRefund(p, "1000.00", "race-" + p.payoutId()));
            awaitLockWaiters(2, 10_000);
            slowWriter.rollback();
            dispatch.get(60, TimeUnit.SECONDS);
            refund.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        long sent = providerPayoutCalls(p.payoutId());
        String status = payoutStatus(p.payoutId());
        if (sent == 0) {
            assertEquals("REVERSED", status);
        } else {
            assertEquals(1, sent);
            assertEquals("SUCCESS", status);
            assertThat(clawbackFlagged(p.payoutId())).as("paid-out refund must be flagged for clawback").isTrue();
        }
    }

    @Test
    void lateFailureCallback_neverDowngradesPaidPayout() {
        Paid p = paidSeat(true);
        payoutService.dispatchPayout(p.payoutId());
        String providerId = jdbc.queryForObject("select provider_payout_id from payouts where id = ?", String.class, p.payoutId());

        paymentService.applyWebhookEvent(GatewayWebhookEvent.builder()
                .kind("PAYOUT").resultStatus("FAILED").externalPaymentId(providerId)
                .providerRequestId(UUID.randomUUID().toString()).build());

        assertEquals("SUCCESS", payoutStatus(p.payoutId()));
    }

    // ------------------------------------------------------------------ refunds vs owner payable

    @Test
    void partialRefundBeforePayout_reducesOwnerPayable() {
        Paid p = paidSeat(false);
        assertEquals(0, new BigDecimal("920.00").compareTo(payoutAmount(p.payoutId())));

        userRefund(p, "500.00", "partial-" + p.payoutId());

        // Owner keeps 92% of what the platform retained: 92% of 500.00.
        assertEquals(0, new BigDecimal("460.00").compareTo(payoutAmount(p.payoutId())),
                "owner payable must shrink with the refund");
        assertThat(payoutStatus(p.payoutId())).isIn("PENDING", "PENDING_METHOD");
    }

    @Test
    void fullRefundBeforePayout_reversesOwnerPayable() {
        Paid p = paidSeat(false);
        userRefund(p, "1000.00", "full-" + p.payoutId());
        assertEquals("REVERSED", payoutStatus(p.payoutId()));
    }

    @Test
    void refundAfterPayout_keepsPayoutAndFlagsClawback() {
        Paid p = paidSeat(true);
        payoutService.dispatchPayout(p.payoutId());
        assertEquals("SUCCESS", payoutStatus(p.payoutId()));

        userRefund(p, "1000.00", "after-" + p.payoutId());

        assertEquals("SUCCESS", payoutStatus(p.payoutId()));
        assertThat(clawbackFlagged(p.payoutId())).isTrue();
    }

    /**
     * An admin-created refund is only PENDING (not yet returned to the payer).
     * Before the fix it was counted as refunded, so a later 50% refund marked
     * the charge REFUNDED_FULL and reversed the whole owner payable.
     */
    @Test
    void pendingRefundIsNotTreatedAsRefundedMoney() {
        Paid p = paidSeat(false);
        User admin = registerAdmin();
        CreateRefundRequest pendingHalf = new CreateRefundRequest();
        pendingHalf.setPaymentTransactionId(p.chargeTxId());
        pendingHalf.setAmount(new BigDecimal("500.00"));
        pendingHalf.setReason("dispute");
        pendingHalf.setIdempotencyKey("admin-half-" + p.payoutId());
        refundService.createRefund(admin, pendingHalf, new MockHttpServletRequest());

        userRefund(p, "500.00", "user-half-" + p.payoutId());

        assertEquals("REFUNDED_PARTIAL", jdbc.queryForObject(
                "select status from payment_transactions where id = ?", String.class, p.chargeTxId()));
        assertThat(payoutStatus(p.payoutId())).isNotEqualTo("REVERSED");
        assertEquals(0, new BigDecimal("460.00").compareTo(payoutAmount(p.payoutId())));
    }

    // ------------------------------------------------------------------ refund caps

    @Test
    void tenConcurrentFullRefunds_differentKeys_refundAtMostTheCapture() throws Exception {
        Paid p = paidSeat(false);
        List<Callable<RefundTransactionResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            tasks.add(() -> userRefund(p, "1000.00", "cc-refund-" + p.payoutId() + "-" + idx));
        }
        List<Outcome<RefundTransactionResponse>> outcomes = runConcurrently(tasks);

        BigDecimal refunded = jdbc.queryForObject("select coalesce(sum(amount),0) from refund_transactions "
                + "where payment_transaction_id = ? and status in ('PENDING','SUCCESS')", BigDecimal.class, p.chargeTxId());
        assertThat(refunded).isLessThanOrEqualTo(new BigDecimal("1000.00"));
        assertEquals(1, outcomes.stream().filter(Outcome::ok).count(), "exactly one full refund");
        for (Outcome<RefundTransactionResponse> o : outcomes) {
            if (!o.ok()) assertThat(o.error()).isInstanceOf(InvalidRequestException.class);
        }
    }

    @Test
    void sameRefundKey_tenConcurrentRetries_oneRefund() throws Exception {
        Paid p = paidSeat(false);
        String key = "same-refund-" + p.payoutId();
        List<Callable<RefundTransactionResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) tasks.add(() -> userRefund(p, "300.00", key));
        List<Outcome<RefundTransactionResponse>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(Outcome::ok);
        assertEquals(1, count("select count(*) from refund_transactions where idempotency_key = ?", key));
        assertEquals(1, mockingDetails(gateway).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("refund")).count(), "one provider refund");
    }

    @Test
    void adminRefunds_cannotExceedRemainingCapture() {
        Paid p = paidSeat(false);
        User admin = registerAdmin();
        CreateRefundRequest first = new CreateRefundRequest();
        first.setPaymentTransactionId(p.chargeTxId());
        first.setAmount(new BigDecimal("1000.00"));
        first.setReason("dispute");
        first.setIdempotencyKey("adm-1-" + p.payoutId());
        refundService.createRefund(admin, first, new MockHttpServletRequest());

        CreateRefundRequest second = new CreateRefundRequest();
        second.setPaymentTransactionId(p.chargeTxId());
        second.setAmount(new BigDecimal("1000.00"));
        second.setReason("dispute again");
        second.setIdempotencyKey("adm-2-" + p.payoutId());
        assertThrows(InvalidRequestException.class,
                () -> refundService.createRefund(admin, second, new MockHttpServletRequest()));
    }
}
