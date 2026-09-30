package kz.hrms.splitupauth.finance;

import kz.hrms.splitupauth.dto.PaymentIntentResponse;
import kz.hrms.splitupauth.dto.RoomResponse;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceConflictException;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Seat reservation and payment-intent idempotency under real PostgreSQL
 * concurrency (20 parallel requests released through a start gate).
 */
class SeatAndPaymentConcurrencyIntegrationTest extends FinancialTestSupport {

    /**
     * Room has exactly one free seat, 20 different applicants pay at once.
     * Before the fix every applicant was charged and marked PENDING (20x
     * overbooking, 20 owner payables) because APPLIED members never held a
     * seat and the capture path never re-checked capacity.
     */
    @Test
    void oneFreeSeat_twentyConcurrentPayers_exactlyOneIsChargedAndSeated() throws Exception {
        User owner = registerVerified("Seat owner");
        RoomResponse room = createRoom(owner, 2); // owner + 1 seat
        List<User> users = new ArrayList<>();
        List<Long> members = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            User u = registerVerified("Applicant " + i);
            users.add(u);
            members.add(join(room.getId(), u));
        }

        List<Callable<PaymentIntentResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            final int idx = i;
            tasks.add(() -> pay(members.get(idx), users.get(idx), "seat-race-" + room.getId() + "-" + idx));
        }
        List<Outcome<PaymentIntentResponse>> outcomes = runConcurrently(tasks);

        assertEquals(1, occupiedSeats(room.getId()), "no overbooking: exactly one seat taken");
        assertEquals(1, chargesInRoom(room.getId()), "only the seated user may be charged");
        assertEquals(1, payoutsInRoom(room.getId()), "exactly one owner payable");

        long winners = outcomes.stream().filter(o -> o.ok() && o.value().getStatus() == PaymentIntentStatus.SUCCESS).count();
        assertEquals(1, winners);
        // Every loser gets the same deterministic 409 — never a 500 or a silent charge.
        for (Outcome<PaymentIntentResponse> o : outcomes) {
            if (o.ok()) continue;
            assertThat(o.error()).isInstanceOf(ResourceConflictException.class);
            assertThat(o.error().getMessage()).contains("ROOM_FULL");
        }
        assertEquals(19, outcomes.stream().filter(o -> !o.ok()).count());
        assertEquals(1, count("select count(*) from room_members where room_id = ? and deleted_at is null "
                + "and user_id = ?", room.getId(), users.get(0).getId()), "no duplicate membership rows");
    }

    /**
     * The same user retries the same payment 20 times concurrently with the
     * same idempotency key. Before the fix 19 requests raced into the UNIQUE
     * index and failed with an unhandled DataIntegrityViolation (HTTP 500).
     */
    @Test
    void sameIdempotencyKey_twentyConcurrentRetries_returnOneLogicalIntent() throws Exception {
        User owner = registerVerified("Idem owner");
        RoomResponse room = createRoom(owner, 4);
        User member = registerVerified("Idem member");
        Long memberId = join(room.getId(), member);
        String key = "same-key-" + memberId;

        List<Callable<PaymentIntentResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) tasks.add(() -> pay(memberId, member, key));
        List<Outcome<PaymentIntentResponse>> outcomes = runConcurrently(tasks);

        List<Throwable> errors = outcomes.stream().filter(o -> !o.ok()).map(Outcome::error).toList();
        assertThat(errors).as("every retry must get the original intent back").isEmpty();
        Set<Long> ids = outcomes.stream().map(o -> o.value().getId()).collect(Collectors.toSet());
        assertEquals(1, ids.size(), "one logical payment intent");

        assertEquals(1, count("select count(*) from payment_intents where room_member_id = ?", memberId));
        assertEquals(1, count("select count(distinct external_payment_id) from payment_intents where room_member_id = ?", memberId),
                "one provider order id");
        assertEquals(1, chargesInRoom(room.getId()), "one payment record");
        assertEquals(1, occupiedSeats(room.getId()), "one membership activation");
        assertEquals(1, payoutsInRoom(room.getId()), "one owner payable");
        verifyChargeCalls(1);
    }

    /**
     * Same membership, 20 DIFFERENT idempotency keys at once (double-click with
     * a fresh key per click). Business rule: one seat purchase per membership, so
     * exactly one charge; the rest are rejected deterministically. Before the
     * fix several requests read APPLIED concurrently and each charged the card.
     */
    @Test
    void differentKeys_sameMembership_concurrently_chargeOnlyOnce() throws Exception {
        User owner = registerVerified("Keys owner");
        RoomResponse room = createRoom(owner, 4);
        User member = registerVerified("Keys member");
        Long memberId = join(room.getId(), member);

        List<Callable<PaymentIntentResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            final int idx = i;
            tasks.add(() -> pay(memberId, member, "diff-key-" + memberId + "-" + idx));
        }
        List<Outcome<PaymentIntentResponse>> outcomes = runConcurrently(tasks);

        assertEquals(1, chargesInRoom(room.getId()), "one capture record");
        verifyChargeCalls(1); // the provider itself must be asked to charge only once
        assertEquals(1, payoutsInRoom(room.getId()), "one owner payable");
        assertEquals(1, occupiedSeats(room.getId()));
        for (Outcome<PaymentIntentResponse> o : outcomes) {
            if (o.ok()) continue;
            // Already paid -> 400 "not APPLIED"; in flight -> 409. Never a 500.
            assertThat(o.error()).isInstanceOfAny(InvalidRequestException.class, ResourceConflictException.class);
        }
    }

    /**
     * A reservation (PENDING intent awaiting the redirect payment) holds the
     * seat; once it expires the seat is released to the next payer, and the
     * abandoned intent is expired by the scheduler job.
     */
    @Test
    void pendingIntentReservesSeat_andAbandonedReservationExpires() {
        gatewayRequiresRedirect();
        User owner = registerVerified("Resv owner");
        RoomResponse room = createRoom(owner, 2);
        User a = registerVerified("Resv A");
        User b = registerVerified("Resv B");
        Long memberA = join(room.getId(), a);
        Long memberB = join(room.getId(), b);

        PaymentIntentResponse intentA = pay(memberA, a, "resv-a-" + memberA);
        assertEquals(PaymentIntentStatus.PENDING, intentA.getStatus());

        ResourceConflictException full = assertThrows(ResourceConflictException.class,
                () -> pay(memberB, b, "resv-b1-" + memberB));
        assertThat(full.getMessage()).contains("ROOM_FULL");

        // A abandons the payment page; the 30-minute window passes.
        jdbc.update("update payment_intents set expires_at = now() - interval '1 minute' where id = ?", intentA.getId());
        paymentService.expireStalePendingIntents();
        assertEquals("FAILED", jdbc.queryForObject("select status from payment_intents where id = ?", String.class, intentA.getId()));

        PaymentIntentResponse intentB = pay(memberB, b, "resv-b2-" + memberB);
        assertEquals(PaymentIntentStatus.PENDING, intentB.getStatus(), "seat released to B");
        assertEquals(0, occupiedSeats(room.getId()), "a reservation is not a paid seat");
    }

    /**
     * The user closes the hosted payment page and clicks "Pay" again, which
     * sends a fresh idempotency key. That is the same seat purchase: they get
     * the still-open provider order back, and no second order is opened.
     */
    @Test
    void newKeyWhileRedirectPaymentOpen_resumesTheSameProviderOrder() {
        gatewayRequiresRedirect();
        User owner = registerVerified("Resume owner");
        RoomResponse room = createRoom(owner, 3);
        User m = registerVerified("Resume member");
        Long memberId = join(room.getId(), m);

        PaymentIntentResponse first = pay(memberId, m, "resume-1-" + memberId);
        PaymentIntentResponse second = pay(memberId, m, "resume-2-" + memberId);

        assertEquals(first.getId(), second.getId());
        assertEquals(first.getPaymentUrl(), second.getPaymentUrl());
        assertEquals(1, count("select count(*) from payment_intents where room_member_id = ?", memberId));
        verifyChargeCalls(1);
    }

    /**
     * The expiry job loads PENDING intents, then a SUCCESS callback commits
     * before the job writes. Before the fix the job's full-row UPDATE blindly
     * overwrote SUCCESS with FAILED (weaker state overwrote the final one).
     */
    @Test
    void expiryJob_neverOverwritesConcurrentSuccess() throws Exception {
        gatewayRequiresRedirect();
        User owner = registerVerified("Exp owner");
        RoomResponse room = createRoom(owner, 3);
        User m = registerVerified("Exp member");
        Long memberId = join(room.getId(), m);
        PaymentIntentResponse intent = pay(memberId, m, "exp-" + memberId);
        jdbc.update("update payment_intents set expires_at = now() - interval '1 minute' where id = ?", intent.getId());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection webhookTx = lockRow("payment_intents", intent.getId())) {
            // The "webhook" holds the row; the expiry job starts meanwhile.
            Future<Outcome<Integer>> job = startAsync(pool, () -> paymentService.expireStalePendingIntents());
            awaitLockWaiters(1, 5_000);
            try (var st = webhookTx.prepareStatement(
                    "update payment_intents set status = 'SUCCESS' where id = ?")) {
                st.setLong(1, intent.getId());
                st.executeUpdate();
            }
            webhookTx.commit();
            job.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals("SUCCESS", jdbc.queryForObject("select status from payment_intents where id = ?",
                String.class, intent.getId()), "expiry must not downgrade a captured payment");
    }

    /**
     * An idempotency key is scoped to one membership. Reusing another user's key
     * must be a conflict, not a disclosure of their intent.
     */
    @Test
    void idempotencyKeyReusedForOtherMembership_isConflict() {
        User owner = registerVerified("Key owner");
        RoomResponse room = createRoom(owner, 4);
        User a = registerVerified("Key A");
        User b = registerVerified("Key B");
        Long memberA = join(room.getId(), a);
        Long memberB = join(room.getId(), b);
        String key = "shared-key-" + room.getId();
        pay(memberA, a, key);

        assertThrows(ResourceConflictException.class, () -> pay(memberB, b, key));
        assertEquals(MemberStatus.APPLIED.name(),
                jdbc.queryForObject("select status from room_members where id = ?", String.class, memberB));
    }

    private void verifyChargeCalls(int expected) {
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.times(expected)).initCharge(org.mockito.ArgumentMatchers.any());
    }
}
