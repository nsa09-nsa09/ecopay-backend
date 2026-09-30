package kz.hrms.splitupauth.finance;

import kz.hrms.splitupauth.controller.FreedomPayWebhookController;
import kz.hrms.splitupauth.dto.PaymentIntentResponse;
import kz.hrms.splitupauth.dto.RoomResponse;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.payment.gateway.GatewayWebhookEvent;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPaySignatureService;
import kz.hrms.splitupauth.service.WebhookInboxService;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Provider callbacks delivered concurrently, duplicated and out of order.
 * The final state must be deterministic and a weaker/older provider state
 * must never overwrite a stronger final one.
 */
class WebhookOrderingIntegrationTest extends FinancialTestSupport {

    @Autowired FreedomPayWebhookController webhookController;
    @Autowired FreedomPaySignatureService signatureService;
    @Autowired WebhookInboxService inboxService;

    private record Pending(RoomResponse room, Long memberId, Long intentId, String externalId) {}

    private Pending pendingRedirectPayment() {
        gatewayRequiresRedirect();
        User owner = registerVerified("Hook owner");
        RoomResponse room = createRoom(owner, 3);
        User m = registerVerified("Hook member");
        Long memberId = join(room.getId(), m);
        PaymentIntentResponse intent = pay(memberId, m, "hook-" + memberId);
        String ext = jdbc.queryForObject("select external_payment_id from payment_intents where id = ?",
                String.class, intent.getId());
        return new Pending(room, memberId, intent.getId(), ext);
    }

    private GatewayWebhookEvent charge(Pending p, String result) {
        return GatewayWebhookEvent.builder()
                .kind("CHARGE")
                .resultStatus(result)
                .intentId(p.intentId())
                .externalPaymentId(p.externalId())
                .amount(SEAT_PRICE)
                .currency("KZT")
                .providerRequestId(p.externalId() + ":" + UUID.randomUUID())
                .build();
    }

    private String intentStatus(Long intentId) {
        return jdbc.queryForObject("select status from payment_intents where id = ?", String.class, intentId);
    }

    private void assertCapturedOnce(Pending p) {
        assertEquals("SUCCESS", intentStatus(p.intentId()));
        assertEquals(1, chargesInRoom(p.room().getId()), "one capture record");
        assertEquals(1, payoutsInRoom(p.room().getId()), "one owner payable");
        assertEquals(1, occupiedSeats(p.room().getId()), "one membership");
    }

    /** A SUCCESS, B SUCCESS, C PENDING, D SUCCESS — concurrently, random order. */
    @RepeatedTest(3)
    void successSuccessPendingSuccess_randomConcurrentOrder_isDeterministic() throws Exception {
        Pending p = pendingRedirectPayment();
        List<GatewayWebhookEvent> events = new ArrayList<>(List.of(
                charge(p, "SUCCESS"), charge(p, "SUCCESS"), charge(p, "PENDING"), charge(p, "SUCCESS")));
        Collections.shuffle(events);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (GatewayWebhookEvent e : events) tasks.add(() -> { paymentService.applyWebhookEvent(e); return null; });
        List<Outcome<Void>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(Outcome::ok);
        assertCapturedOnce(p);
    }

    @Test
    void successThenFailure_failureIsIgnored() {
        Pending p = pendingRedirectPayment();
        paymentService.applyWebhookEvent(charge(p, "SUCCESS"));
        paymentService.applyWebhookEvent(charge(p, "FAILED"));
        assertCapturedOnce(p);
    }

    @Test
    void failureThenLateSuccess_captureIsHonoured() {
        Pending p = pendingRedirectPayment();
        paymentService.applyWebhookEvent(charge(p, "FAILED"));
        assertEquals("FAILED", intentStatus(p.intentId()));
        paymentService.applyWebhookEvent(charge(p, "SUCCESS"));
        assertCapturedOnce(p);
    }

    @Test
    void pendingThenSuccess_reachesSuccess() {
        Pending p = pendingRedirectPayment();
        paymentService.applyWebhookEvent(charge(p, "PENDING"));
        assertEquals("PENDING", intentStatus(p.intentId()));
        paymentService.applyWebhookEvent(charge(p, "SUCCESS"));
        assertCapturedOnce(p);
    }

    @Test
    void successThenDuplicateSuccess_isNoop() {
        Pending p = pendingRedirectPayment();
        GatewayWebhookEvent success = charge(p, "SUCCESS");
        paymentService.applyWebhookEvent(success);
        paymentService.applyWebhookEvent(success);
        paymentService.applyWebhookEvent(charge(p, "SUCCESS"));
        assertCapturedOnce(p);
    }

    /**
     * Intent A expires (user abandoned the page), the user pays again with
     * intent B, and then the provider reports A as captured after all.
     * Before the fix this created a second CHARGE and a second owner payable
     * for the same seat. Now A is recorded (money is real) but flagged for
     * refund review and credits nobody.
     */
    @Test
    void lateSuccessOfExpiredIntent_afterSeatPaidByAnotherIntent_createsNoSecondPayable() {
        Pending a = pendingRedirectPayment();
        jdbc.update("update payment_intents set expires_at = now() - interval '1 minute' where id = ?", a.intentId());
        paymentService.expireStalePendingIntents();

        User member = userRepository.findById(jdbc.queryForObject(
                "select user_id from room_members where id = ?", Long.class, a.memberId())).orElseThrow();
        PaymentIntentResponse b = pay(a.memberId(), member, "hook-retry-" + a.memberId());
        paymentService.applyWebhookEvent(GatewayWebhookEvent.builder()
                .kind("CHARGE").resultStatus("SUCCESS").intentId(b.getId())
                .externalPaymentId(jdbc.queryForObject("select external_payment_id from payment_intents where id = ?",
                        String.class, b.getId()))
                .amount(SEAT_PRICE).currency("KZT").providerRequestId(UUID.randomUUID().toString()).build());
        assertEquals(1, payoutsInRoom(a.room().getId()));

        // Late capture of the abandoned intent.
        paymentService.applyWebhookEvent(charge(a, "SUCCESS"));

        assertEquals(1, payoutsInRoom(a.room().getId()), "no duplicate owner payable");
        assertEquals(1, occupiedSeats(a.room().getId()));
        assertEquals(2, chargesInRoom(a.room().getId()), "the real second capture is still recorded");
        assertEquals("DUPLICATE_CAPTURE", jdbc.queryForObject(
                "select manual_review_reason from payment_intents where id = ?", String.class, a.intentId()));
    }

    /**
     * Inbox row is written, then processing crashes (simulated by a DB trigger
     * that aborts the intent update). Before the fix the provider's redelivery
     * was answered "duplicate, ok" and the capture was lost forever.
     */
    @Test
    void webhookProcessingCrash_redeliveryIsProcessedExactlyOnce() {
        Pending p = pendingRedirectPayment();
        Map<String, String> params = new HashMap<>();
        params.put("pg_order_id", String.valueOf(p.intentId()));
        params.put("pg_payment_id", p.externalId());
        params.put("pg_amount", "1000.00");
        params.put("pg_currency", "KZT");
        params.put("pg_result", "1");
        params.put("pg_salt", "salt-" + UUID.randomUUID());
        params.put("pg_sig", signatureService.signWithMerchantSecret("result", params));

        installCrashTrigger(p.intentId());
        try {
            webhookController.result(new HashMap<>(params));
        } finally {
            jdbc.execute("drop trigger if exists test_crash_intent on payment_intents");
        }
        assertEquals("PENDING", intentStatus(p.intentId()), "crashed processing must not half-apply");

        webhookController.result(new HashMap<>(params)); // provider redelivers the same callback
        webhookController.result(new HashMap<>(params)); // ...twice

        assertCapturedOnce(p);
        assertEquals(1, count("select count(*) from freedom_webhook_inbox where raw_body ->> 'pg_payment_id' = ?",
                p.externalId()), "one inbox row per provider request");
        assertEquals("PROCESSED", jdbc.queryForObject(
                "select processing_status from freedom_webhook_inbox where raw_body ->> 'pg_payment_id' = ?",
                String.class, p.externalId()));
    }

    /** Processing crashed and the provider never redelivers: the inbox retry job finishes it once. */
    @Test
    void webhookProcessingCrash_withoutRedelivery_isFinishedByInboxRetryJob() {
        Pending p = pendingRedirectPayment();
        Map<String, String> params = new HashMap<>();
        params.put("pg_order_id", String.valueOf(p.intentId()));
        params.put("pg_payment_id", p.externalId());
        params.put("pg_amount", "1000.00");
        params.put("pg_currency", "KZT");
        params.put("pg_result", "1");
        params.put("pg_salt", "salt-" + UUID.randomUUID());
        params.put("pg_sig", signatureService.signWithMerchantSecret("result", params));

        installCrashTrigger(p.intentId());
        try {
            webhookController.result(new HashMap<>(params));
        } finally {
            jdbc.execute("drop trigger if exists test_crash_intent on payment_intents");
        }
        assertEquals("FAILED", jdbc.queryForObject(
                "select processing_status from freedom_webhook_inbox where raw_body ->> 'pg_payment_id' = ?",
                String.class, p.externalId()));

        jdbc.update("update freedom_webhook_inbox set received_at = now() - interval '5 minutes' "
                + "where raw_body ->> 'pg_payment_id' = ?", p.externalId());
        inboxService.retryUnprocessed();
        inboxService.retryUnprocessed();

        assertCapturedOnce(p);
        assertEquals("PROCESSED", jdbc.queryForObject(
                "select processing_status from freedom_webhook_inbox where raw_body ->> 'pg_payment_id' = ?",
                String.class, p.externalId()));
    }

    private void installCrashTrigger(Long intentId) {
        jdbc.execute("""
                create or replace function test_crash_fn() returns trigger as $$
                begin raise exception 'simulated crash during webhook processing'; end;
                $$ language plpgsql""");
        jdbc.execute("drop trigger if exists test_crash_intent on payment_intents");
        jdbc.execute("create trigger test_crash_intent before update on payment_intents for each row "
                + "when (new.id = " + intentId + " and new.status = 'SUCCESS') execute function test_crash_fn()");
    }
}
