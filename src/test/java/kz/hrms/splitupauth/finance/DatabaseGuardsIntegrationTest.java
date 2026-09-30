package kz.hrms.splitupauth.finance;

import kz.hrms.splitupauth.dto.ConfirmOwnerAccessRequest;
import kz.hrms.splitupauth.dto.PaymentIntentResponse;
import kz.hrms.splitupauth.dto.RoomResponse;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.RecurringChargeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The V32 constraints must hold even for writes that bypass the service layer
 * (a future code path, a manual SQL fix, a buggy job). Also checks that
 * idempotency does not swallow a legitimate separate payment.
 */
class DatabaseGuardsIntegrationTest extends FinancialTestSupport {

    @Autowired RecurringChargeService recurringChargeService;

    private record Seat(User owner, User member, RoomResponse room, Long memberId, Long intentId) {}

    private Seat paidSeat(int maxMembers) {
        User owner = registerVerified("Guard owner");
        RoomResponse room = createRoom(owner, maxMembers);
        User member = registerVerified("Guard member");
        Long memberId = join(room.getId(), member);
        PaymentIntentResponse intent = pay(memberId, member, "guard-" + memberId);
        return new Seat(owner, member, room, memberId, intent.getId());
    }

    @Test
    void capacityTrigger_rejectsOverbookingWrittenBehindTheServices() {
        User owner = registerVerified("Guard owner");
        RoomResponse room = createRoom(owner, 2); // one member seat
        User payer = registerVerified("Guard payer");
        Long payerMember = join(room.getId(), payer);
        Long other = join(room.getId(), registerVerified("Guard intruder")); // APPLIED holds no seat
        pay(payerMember, payer, "guard-cap-" + payerMember);
        Seat s = new Seat(owner, payer, room, payerMember, null);

        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update room_members set status = 'PENDING' where id = ?", other));
        assertThat(ex.getMessage()).contains("ROOM_CAPACITY_EXCEEDED");
        assertEquals(1, occupiedSeats(s.room().getId()));
    }

    @Test
    void roomCannotBeShrunkBelowItsOccupiedSeats() {
        Seat s = paidSeat(3);
        User second = registerVerified("Guard second");
        pay(join(s.room().getId(), second), second, "guard-2-" + s.memberId());
        assertEquals(2, occupiedSeats(s.room().getId()));

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update rooms set max_members = 2 where id = ?", s.room().getId()));
    }

    @Test
    void refundCapTrigger_rejectsRefundsAboveCapture() {
        Seat s = paidSeat(3);
        Long txId = jdbc.queryForObject("select id from payment_transactions where payment_intent_id = ?", Long.class, s.intentId());
        jdbc.update("insert into refund_transactions (payment_transaction_id, status, amount, currency, reason, idempotency_key) "
                + "values (?, 'SUCCESS', 700.00, 'KZT', 'x', ?)", txId, "guard-r1-" + txId);

        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("insert into refund_transactions (payment_transaction_id, status, amount, currency, reason, idempotency_key) "
                        + "values (?, 'PENDING', 300.01, 'KZT', 'x', ?)", txId, "guard-r2-" + txId));
        assertThat(ex.getMessage()).contains("REFUND_EXCEEDS_CAPTURE");
    }

    @Test
    void payoutCapTrigger_rejectsPayoutAboveCapture() {
        Seat s = paidSeat(3);
        Long payoutId = payoutIdForRoom(s.room().getId());
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update payouts set amount = 1000.01 where id = ?", payoutId));
        assertThat(ex.getMessage()).contains("PAYOUT_EXCEEDS_CAPTURE");
    }

    @Test
    void uniqueGuards_oneChargeAndOnePayoutPerIntent_onePendingIntentPerMembership() {
        Seat s = paidSeat(3);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                insert into payment_transactions (payment_intent_id, room_id, room_member_id, type, status, amount, currency)
                values (?, ?, ?, 'CHARGE', 'SUCCESS', 1000.00, 'KZT')""", s.intentId(), s.room().getId(), s.memberId()));

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                insert into payouts (user_id, room_id, amount, currency, status, idempotency_key, triggering_payment_intent_id)
                values (?, ?, 1.00, 'KZT', 'PENDING', ?, ?)""", s.owner().getId(), s.room().getId(), "dup-" + s.intentId(), s.intentId()));

        Long applied = join(s.room().getId(), registerVerified("Guard pending"));
        Long userId = jdbc.queryForObject("select user_id from room_members where id = ?", Long.class, applied);
        String insertPending = "insert into payment_intents (room_member_id, user_id, amount, currency, status, idempotency_key, expires_at) "
                + "values (?, ?, 1000.00, 'KZT', 'PENDING', ?, now() + interval '30 minutes')";
        jdbc.update(insertPending, applied, userId, "guard-p1-" + applied);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insertPending, applied, userId, "guard-p2-" + applied));
    }

    @Test
    void negativeOrZeroMoney_isRejected() {
        Seat s = paidSeat(3);
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update payment_intents set amount = 0 where id = ?", s.intentId()));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update payouts set amount = -1 where room_id = ?", s.room().getId()));
    }

    /**
     * A renewal of an ACTIVE membership uses a different idempotency key and
     * must NOT be deduplicated against the seat payment: it is a second,
     * legitimate capture with its own owner payable. Re-running the same
     * billing period is deduplicated.
     */
    @Test
    void recurringRenewal_isALegitimateSeparatePayment_butOncePerPeriod() {
        Seat s = paidSeat(3);
        ConfirmOwnerAccessRequest grant = new ConfirmOwnerAccessRequest();
        grant.setAccessMethod("invite_link");
        roomMemberService.confirmOwnerAccess(s.room().getId(), s.memberId(), s.owner(), grant);
        roomMemberService.confirmMemberAccess(s.room().getId(), s.member());
        assertEquals("ACTIVE", jdbc.queryForObject("select status from room_members where id = ?", String.class, s.memberId()));

        jdbc.update("update payment_intents set created_at = now() - interval '29 days' where id = ?", s.intentId());
        jdbc.update("insert into saved_cards (user_id, provider_name, provider_token, pan_mask, is_default, status) "
                + "values (?, 'freedompay', ?, '4400****1111', true, 'ACTIVE')", s.member().getId(), "card-" + s.memberId());

        recurringChargeService.tryAutoCharge(s.memberId());
        recurringChargeService.tryAutoCharge(s.memberId());

        assertEquals(2, chargesInRoom(s.room().getId()), "seat payment + one renewal");
        assertEquals(2, payoutsInRoom(s.room().getId()), "each capture has its own owner payable");
        assertEquals(1, count("select count(*) from payment_intents where room_member_id = ? and purpose = 'RECURRING'", s.memberId()));
        assertEquals(1, occupiedSeats(s.room().getId()), "a renewal does not take another seat");
    }
}
