package kz.hrms.splitupauth.finance;

import kz.hrms.splitupauth.dto.CreateRefundRequest;
import kz.hrms.splitupauth.dto.RoomResponse;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.PayoutService;
import kz.hrms.splitupauth.service.RefundService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives a randomized, concurrent mix of payments, refunds and payout
 * dispatches, then verifies the ledger invariants directly in SQL.
 *
 * <p>Invariants that the database itself now enforces (capacity, refund cap,
 * payout cap, non-negative money) are checked over the WHOLE database; the
 * allocation invariants are checked for the rooms this suite created.
 */
class FinancialInvariantsIntegrationTest extends FinancialTestSupport {

    @Autowired PayoutService payoutService;
    @Autowired RefundService refundService;

    @Value("${app.platform.fee-percent:8}")
    int feePercent;

    private static final String MY_ROOMS = "select id from rooms where title like 'Fin room %'";

    @Test
    void randomizedConcurrentWorkload_preservesLedgerInvariants() throws Exception {
        Random rnd = new Random(42);
        List<Callable<Object>> payments = new ArrayList<>();
        for (int r = 0; r < 4; r++) {
            User owner = registerVerified("Inv owner " + r);
            if (r % 2 == 0) givePayoutMethod(owner);
            RoomResponse room = createRoom(owner, 3); // 2 member seats
            for (int i = 0; i < 5; i++) {
                User u = registerVerified("Inv member " + r + "-" + i);
                Long memberId = join(room.getId(), u);
                final int attempt = i;
                payments.add(() -> pay(memberId, u, "inv-" + memberId + "-" + attempt));
                payments.add(() -> pay(memberId, u, "inv-" + memberId + "-" + attempt)); // retry, same key
            }
        }
        runConcurrently(payments);

        // Refunds (random partial/full, some concurrent duplicates) racing payout dispatch.
        List<Map<String, Object>> charges = jdbc.queryForList("""
                select t.id as tx_id, t.amount, pi.user_id, p.id as payout_id
                  from payment_transactions t
                  join payment_intents pi on pi.id = t.payment_intent_id
                  left join payouts p on p.triggering_payment_intent_id = pi.id
                 where t.type = 'CHARGE' and t.room_id in (""" + MY_ROOMS + ")");
        List<Callable<Object>> money = new ArrayList<>();
        for (Map<String, Object> c : charges) {
            Long txId = ((Number) c.get("tx_id")).longValue();
            User payer = userRepository.findById(((Number) c.get("user_id")).longValue()).orElseThrow();
            String amount = rnd.nextBoolean() ? "1000.00" : "400.00";
            for (int k = 0; k < 2; k++) {
                final int n = k;
                money.add(() -> {
                    CreateRefundRequest req = new CreateRefundRequest();
                    req.setPaymentTransactionId(txId);
                    req.setAmount(new BigDecimal(amount));
                    req.setReason("invariant workload");
                    req.setIdempotencyKey("inv-refund-" + txId + "-" + n);
                    return refundService.requestRefund(payer, req);
                });
            }
            if (c.get("payout_id") != null) {
                Long payoutId = ((Number) c.get("payout_id")).longValue();
                money.add(() -> { payoutService.dispatchPayout(payoutId); return null; });
            }
        }
        runConcurrently(money);

        assertInvariants();
    }

    private void assertInvariants() {
        // --- global, DB-enforced ------------------------------------------------
        assertThat(jdbc.queryForList("""
                select r.id from rooms r
                 where (select count(*) from room_members m where m.room_id = r.id and m.deleted_at is null
                          and m.status in ('PENDING','ACTIVE')) > r.max_members - 1""", Long.class))
                .as("occupied seats <= capacity for every room").isEmpty();

        assertThat(jdbc.queryForList("""
                select t.id from payment_transactions t
                 where (select coalesce(sum(r.amount),0) from refund_transactions r
                         where r.payment_transaction_id = t.id and r.status in ('PENDING','SUCCESS')) > t.amount""", Long.class))
                .as("refunded <= captured for every charge").isEmpty();

        assertThat(jdbc.queryForList("""
                select p.id from payouts p join payment_intents pi on pi.id = p.triggering_payment_intent_id
                 where p.amount > pi.amount""", Long.class))
                .as("payout <= captured payment").isEmpty();

        assertThat(count("""
                select (select count(*) from payment_intents where amount <= 0)
                     + (select count(*) from payment_transactions where amount <= 0)
                     + (select count(*) from refund_transactions where amount <= 0)
                     + (select count(*) from payouts where amount < 0)""")).as("no negative money").isZero();

        assertThat(count("""
                select count(*) from room_members m
                  left join rooms r on r.id = m.room_id left join users u on u.id = m.user_id
                 where r.id is null or u.id is null""")).as("every membership has a room and a user").isZero();

        assertThat(count("""
                select count(*) from (select payment_intent_id from payment_transactions where type = 'CHARGE'
                                       group by payment_intent_id having count(*) > 1) d"""))
                .as("one capture record per intent").isZero();

        assertThat(count("""
                select count(*) from (select triggering_payment_intent_id from payouts
                                       where triggering_payment_intent_id is not null
                                       group by triggering_payment_intent_id having count(*) > 1) d"""))
                .as("one owner payable per captured payment").isZero();

        // --- allocation, for this suite's rooms ---------------------------------
        assertThat(jdbc.queryForList("""
                select m.id from room_members m
                 where m.room_id in (""" + MY_ROOMS + """
                ) and m.deleted_at is null and m.status in ('PENDING','ACTIVE')
                   and not exists (select 1 from payment_transactions t
                                    where t.room_member_id = m.id and t.type = 'CHARGE')""", Long.class))
                .as("no membership without a successful payment").isEmpty();

        assertThat(jdbc.queryForList("""
                select t.id from payment_transactions t
                  join payment_intents pi on pi.id = t.payment_intent_id
                  join room_members m on m.id = pi.room_member_id
                 where t.type = 'CHARGE' and t.room_id in (""" + MY_ROOMS + """
                ) and pi.manual_review_reason is null
                   and not (m.status in ('PENDING','ACTIVE') and m.deleted_at is null)""", Long.class))
                .as("no successful payment without membership unless flagged for manual review").isEmpty();

        assertThat(jdbc.queryForList("""
                select t.id from payment_transactions t
                  join payment_intents pi on pi.id = t.payment_intent_id
                 where t.type = 'CHARGE' and t.room_id in (""" + MY_ROOMS + """
                ) and (select count(*) from payouts p where p.triggering_payment_intent_id = pi.id)
                       <> case when pi.manual_review_reason is null then 1 else 0 end""", Long.class))
                .as("every credited capture has exactly one owner payable; flagged captures have none").isEmpty();

        // captured = refunded + retained; retained = owner payable + platform revenue (fee), while unpaid.
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select t.amount as captured, p.id as payout_id, p.amount as payable, p.status,
                       (select coalesce(sum(r.amount),0) from refund_transactions r
                         where r.payment_transaction_id = t.id and r.status = 'SUCCESS') as refunded
                  from payment_transactions t
                  join payouts p on p.triggering_payment_intent_id = t.payment_intent_id
                 where t.type = 'CHARGE' and t.room_id in (""" + MY_ROOMS + ")");
        assertThat(rows).isNotEmpty();
        for (Map<String, Object> r : rows) {
            BigDecimal captured = (BigDecimal) r.get("captured");
            BigDecimal refunded = (BigDecimal) r.get("refunded");
            BigDecimal payable = (BigDecimal) r.get("payable");
            String status = (String) r.get("status");
            Long payoutId = ((Number) r.get("payout_id")).longValue();
            BigDecimal retained = captured.subtract(refunded);
            assertThat(retained.signum()).as("refunded <= captured").isGreaterThanOrEqualTo(0);
            switch (status) {
                case "PENDING", "PENDING_METHOD" -> {
                    BigDecimal fee = retained.multiply(BigDecimal.valueOf(feePercent))
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                    assertThat(payable).as("unpaid payable = retained - platform fee (payout " + payoutId + ")")
                            .isEqualByComparingTo(retained.subtract(fee));
                    assertThat(retained).isEqualByComparingTo(payable.add(fee));
                }
                case "REVERSED" -> assertThat(refunded).as("reversed only when fully refunded")
                        .isEqualByComparingTo(captured);
                case "SUCCESS", "PROCESSING", "UNKNOWN" -> {
                    if (refunded.signum() > 0) {
                        assertThat(count("select count(*) from payment_event_log where entity_type = 'PAYOUT' "
                                + "and entity_id = ? and event_type = 'CLAWBACK_REQUIRED'", payoutId))
                                .as("refund after dispatch must be flagged for clawback (payout " + payoutId + ")")
                                .isPositive();
                    } else {
                        assertThat(payable).isLessThanOrEqualTo(captured);
                    }
                }
                default -> { }
            }
        }
    }
}
