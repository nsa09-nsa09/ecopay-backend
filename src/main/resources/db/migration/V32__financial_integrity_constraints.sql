-- =========================================================
-- V32 — Financial integrity: constraints, invariant triggers and indexes.
--
-- Corrective migration from the DB integrity / concurrency audit. Nothing
-- here rewrites an earlier migration. What it enforces at the database level
-- (so a concurrent request, a retry or a buggy code path cannot violate it):
--
--   * at most ONE in-flight (PENDING) payment intent per membership
--   * at most ONE CHARGE transaction per payment intent
--   * at most ONE owner payout per captured payment intent
--   * occupied seats (PENDING + ACTIVE members) <= max_members - 1 (owner seat)
--   * sum of PENDING + SUCCESS refunds <= captured amount of the charge
--   * payout amount <= amount of the payment that triggered it
--   * no negative / zero money amounts
--   * payout status is one of a known set (incl. new UNKNOWN for lost
--     provider responses that must be reconciled, never blindly retried)
--
-- Legacy data: duplicated PENDING intents for one membership are superseded
-- (they never captured money; a late provider SUCCESS is still honoured by the
-- application and routed to manual review). Duplicated CHARGE rows or owner
-- payouts are NOT auto-fixed — they are real money and need a human decision,
-- so the migration aborts with a descriptive error instead.
-- =========================================================

-- ---------------------------------------------------------
-- 1. New columns
-- ---------------------------------------------------------

-- Optimistic-lock versions: guard JPA full-row updates against lost updates
-- (e.g. the intent-expiry job overwriting a concurrently committed SUCCESS).
ALTER TABLE payment_intents ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payouts         ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- INITIAL = the payment that buys the seat; RECURRING = renewal of an ACTIVE
-- membership. The capture handler needs this to tell a legitimate renewal from
-- a duplicate capture of the joining payment.
ALTER TABLE payment_intents ADD COLUMN IF NOT EXISTS purpose VARCHAR(20) NOT NULL DEFAULT 'INITIAL';
UPDATE payment_intents SET purpose = 'RECURRING'
 WHERE idempotency_key LIKE 'recurring-%' AND purpose <> 'RECURRING';

-- Set when money was captured but could not be credited to a seat (room full,
-- duplicate capture, membership no longer payable). Such captures create NO
-- owner payable and must be refunded / resolved by an operator.
ALTER TABLE payment_intents ADD COLUMN IF NOT EXISTS manual_review_reason VARCHAR(50);

-- Webhook inbox: remember which callback endpoint (signature script) received
-- the row so a crashed row can be re-verified and re-processed by the retry job.
ALTER TABLE freedom_webhook_inbox ADD COLUMN IF NOT EXISTS endpoint VARCHAR(50);
ALTER TABLE freedom_webhook_inbox ADD COLUMN IF NOT EXISTS attempts INTEGER NOT NULL DEFAULT 0;

-- ---------------------------------------------------------
-- 2. Legacy data remediation / pre-flight checks
-- ---------------------------------------------------------

-- Keep only the newest PENDING intent per membership.
UPDATE payment_intents pi
   SET status          = 'FAILED',
       failure_code    = 'SUPERSEDED',
       failure_message = 'Superseded by a newer pending intent (V32 migration)',
       updated_at      = CURRENT_TIMESTAMP
 WHERE pi.status = 'PENDING'
   AND EXISTS (SELECT 1 FROM payment_intents newer
                WHERE newer.room_member_id = pi.room_member_id
                  AND newer.status = 'PENDING'
                  AND newer.id > pi.id);

DO $$
DECLARE
    dup_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO dup_count FROM (
        SELECT payment_intent_id FROM payment_transactions
         WHERE type = 'CHARGE'
         GROUP BY payment_intent_id HAVING COUNT(*) > 1) d;
    IF dup_count > 0 THEN
        RAISE EXCEPTION 'V32: % payment intent(s) have more than one CHARGE transaction. Resolve manually: SELECT payment_intent_id, array_agg(id) FROM payment_transactions WHERE type = ''CHARGE'' GROUP BY 1 HAVING COUNT(*) > 1', dup_count;
    END IF;

    SELECT COUNT(*) INTO dup_count FROM (
        SELECT triggering_payment_intent_id FROM payouts
         WHERE triggering_payment_intent_id IS NOT NULL
         GROUP BY triggering_payment_intent_id HAVING COUNT(*) > 1) d;
    IF dup_count > 0 THEN
        RAISE EXCEPTION 'V32: % payment intent(s) have more than one owner payout. Resolve manually: SELECT triggering_payment_intent_id, array_agg(id || '':'' || status) FROM payouts GROUP BY 1 HAVING COUNT(*) > 1', dup_count;
    END IF;
END $$;

-- ---------------------------------------------------------
-- 3. CHECK constraints (added NOT VALID, then validated)
-- ---------------------------------------------------------

ALTER TABLE payment_intents DROP CONSTRAINT IF EXISTS chk_payment_intents_amount_positive;
ALTER TABLE payment_intents ADD CONSTRAINT chk_payment_intents_amount_positive
    CHECK (amount > 0) NOT VALID;
ALTER TABLE payment_intents VALIDATE CONSTRAINT chk_payment_intents_amount_positive;

ALTER TABLE payment_intents DROP CONSTRAINT IF EXISTS chk_payment_intents_purpose;
ALTER TABLE payment_intents ADD CONSTRAINT chk_payment_intents_purpose
    CHECK (purpose IN ('INITIAL', 'RECURRING')) NOT VALID;
ALTER TABLE payment_intents VALIDATE CONSTRAINT chk_payment_intents_purpose;

ALTER TABLE payment_transactions DROP CONSTRAINT IF EXISTS chk_payment_transactions_amount_positive;
ALTER TABLE payment_transactions ADD CONSTRAINT chk_payment_transactions_amount_positive
    CHECK (amount > 0) NOT VALID;
ALTER TABLE payment_transactions VALIDATE CONSTRAINT chk_payment_transactions_amount_positive;

ALTER TABLE refund_transactions DROP CONSTRAINT IF EXISTS chk_refund_transactions_amount_positive;
ALTER TABLE refund_transactions ADD CONSTRAINT chk_refund_transactions_amount_positive
    CHECK (amount > 0) NOT VALID;
ALTER TABLE refund_transactions VALIDATE CONSTRAINT chk_refund_transactions_amount_positive;

ALTER TABLE payouts DROP CONSTRAINT IF EXISTS chk_payouts_amount_non_negative;
ALTER TABLE payouts ADD CONSTRAINT chk_payouts_amount_non_negative
    CHECK (amount >= 0) NOT VALID;
ALTER TABLE payouts VALIDATE CONSTRAINT chk_payouts_amount_non_negative;

ALTER TABLE payouts DROP CONSTRAINT IF EXISTS chk_payouts_retry_count_non_negative;
ALTER TABLE payouts ADD CONSTRAINT chk_payouts_retry_count_non_negative
    CHECK (retry_count >= 0) NOT VALID;
ALTER TABLE payouts VALIDATE CONSTRAINT chk_payouts_retry_count_non_negative;

ALTER TABLE payouts DROP CONSTRAINT IF EXISTS chk_payouts_status;
ALTER TABLE payouts ADD CONSTRAINT chk_payouts_status
    CHECK (status IN ('PENDING', 'PENDING_METHOD', 'PROCESSING', 'UNKNOWN',
                      'SUCCESS', 'FAILED', 'REVERSED', 'CANCELED')) NOT VALID;
ALTER TABLE payouts VALIDATE CONSTRAINT chk_payouts_status;

ALTER TABLE rooms DROP CONSTRAINT IF EXISTS chk_rooms_prices_non_negative;
ALTER TABLE rooms ADD CONSTRAINT chk_rooms_prices_non_negative
    CHECK ((price_total IS NULL OR price_total >= 0)
       AND (price_per_member IS NULL OR price_per_member >= 0)) NOT VALID;
ALTER TABLE rooms VALIDATE CONSTRAINT chk_rooms_prices_non_negative;

-- ---------------------------------------------------------
-- 4. Uniqueness guards (idempotency at the DB level)
-- ---------------------------------------------------------

-- One in-flight payment per membership: a second concurrent "pay" click with a
-- different idempotency key cannot open a second provider order.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_intents_one_pending_per_member
    ON payment_intents (room_member_id)
    WHERE status = 'PENDING';

-- One capture record per intent: duplicate SUCCESS callbacks cannot double-book.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_transactions_one_charge_per_intent
    ON payment_transactions (payment_intent_id)
    WHERE type = 'CHARGE';

-- One owner payable per captured payment.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payouts_triggering_payment_intent
    ON payouts (triggering_payment_intent_id)
    WHERE triggering_payment_intent_id IS NOT NULL;

-- Provider references are looked up by the webhook handlers and must be unambiguous.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payouts_provider_payout_id
    ON payouts (provider_payout_id)
    WHERE provider_payout_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_refund_transactions_provider_refund_id
    ON refund_transactions (provider_refund_id)
    WHERE provider_refund_id IS NOT NULL;

-- ---------------------------------------------------------
-- 5. Indexes for hot financial queries / FK sides
-- ---------------------------------------------------------

-- Refund cap check + sumActiveRefundAmounts() look refunds up by parent charge.
CREATE INDEX IF NOT EXISTS idx_refund_transactions_payment_tx_status
    ON refund_transactions (payment_transaction_id, status);

CREATE INDEX IF NOT EXISTS idx_refund_transactions_dispute_id
    ON refund_transactions (dispute_id)
    WHERE dispute_id IS NOT NULL;

-- Expiry scheduler: PENDING intents past expires_at (tiny partial index).
CREATE INDEX IF NOT EXISTS idx_payment_intents_pending_expires_at
    ON payment_intents (expires_at)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_payment_intents_user_created_at
    ON payment_intents (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_payment_intents_external_payment_id
    ON payment_intents (external_payment_id)
    WHERE external_payment_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_payment_intents_manual_review
    ON payment_intents (created_at)
    WHERE manual_review_reason IS NOT NULL;

-- existsByRoomMember_IdAndStatus() (identifier reveal) and per-room revenue.
CREATE INDEX IF NOT EXISTS idx_payment_transactions_room_member_status
    ON payment_transactions (room_member_id, status);

CREATE INDEX IF NOT EXISTS idx_payment_transactions_room_id
    ON payment_transactions (room_id);

CREATE INDEX IF NOT EXISTS idx_payouts_room_id
    ON payouts (room_id);

-- Nightly cleanup deletes login_attempts by attempt_time alone; the existing
-- (email, attempt_time) index cannot serve that range scan.
CREATE INDEX IF NOT EXISTS idx_login_attempts_attempt_time
    ON login_attempts (attempt_time);

-- ---------------------------------------------------------
-- 6. Invariant triggers
--    Each trigger first locks the parent row, so the check is race-free under
--    READ COMMITTED: a concurrent transaction making the same check waits for
--    the lock and then re-counts with a fresh snapshot.
-- ---------------------------------------------------------

-- 6a. Room capacity. The owner occupies one of max_members.
CREATE OR REPLACE FUNCTION enforce_room_member_capacity()
RETURNS TRIGGER AS $$
DECLARE
    v_max      INTEGER;
    v_occupied BIGINT;
BEGIN
    IF NEW.deleted_at IS NOT NULL OR NEW.status NOT IN ('PENDING', 'ACTIVE') THEN
        RETURN NEW;
    END IF;
    -- Only a transition INTO an occupying state consumes a seat.
    IF TG_OP = 'UPDATE'
       AND OLD.room_id = NEW.room_id
       AND OLD.deleted_at IS NULL
       AND OLD.status IN ('PENDING', 'ACTIVE') THEN
        RETURN NEW;
    END IF;

    SELECT max_members INTO v_max FROM rooms WHERE id = NEW.room_id FOR UPDATE;

    SELECT COUNT(*) INTO v_occupied
      FROM room_members
     WHERE room_id = NEW.room_id
       AND deleted_at IS NULL
       AND status IN ('PENDING', 'ACTIVE');

    IF v_occupied > v_max - 1 THEN
        RAISE EXCEPTION 'ROOM_CAPACITY_EXCEEDED: room % has % occupied seats, capacity %',
            NEW.room_id, v_occupied, v_max - 1
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_room_members_capacity ON room_members;
CREATE TRIGGER trg_room_members_capacity
    AFTER INSERT OR UPDATE OF status, deleted_at, room_id ON room_members
    FOR EACH ROW EXECUTE FUNCTION enforce_room_member_capacity();

-- 6b. A room cannot be shrunk below its occupied seats.
CREATE OR REPLACE FUNCTION enforce_room_resize_capacity()
RETURNS TRIGGER AS $$
DECLARE
    v_occupied BIGINT;
BEGIN
    SELECT COUNT(*) INTO v_occupied
      FROM room_members
     WHERE room_id = NEW.id
       AND deleted_at IS NULL
       AND status IN ('PENDING', 'ACTIVE');
    IF v_occupied > NEW.max_members - 1 THEN
        RAISE EXCEPTION 'ROOM_CAPACITY_EXCEEDED: room % has % occupied seats, cannot shrink to %',
            NEW.id, v_occupied, NEW.max_members
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_rooms_resize_capacity ON rooms;
CREATE TRIGGER trg_rooms_resize_capacity
    BEFORE UPDATE OF max_members ON rooms
    FOR EACH ROW
    WHEN (NEW.max_members < OLD.max_members)
    EXECUTE FUNCTION enforce_room_resize_capacity();

-- 6c. Refunds never exceed the captured amount.
CREATE OR REPLACE FUNCTION enforce_refund_cap()
RETURNS TRIGGER AS $$
DECLARE
    v_captured NUMERIC(12,2);
    v_refunded NUMERIC(14,2);
BEGIN
    IF NEW.status NOT IN ('PENDING', 'SUCCESS') THEN
        RETURN NEW;
    END IF;

    SELECT amount INTO v_captured
      FROM payment_transactions WHERE id = NEW.payment_transaction_id FOR UPDATE;

    SELECT COALESCE(SUM(amount), 0) INTO v_refunded
      FROM refund_transactions
     WHERE payment_transaction_id = NEW.payment_transaction_id
       AND status IN ('PENDING', 'SUCCESS');

    IF v_refunded > v_captured THEN
        RAISE EXCEPTION 'REFUND_EXCEEDS_CAPTURE: payment transaction % captured %, refunds would total %',
            NEW.payment_transaction_id, v_captured, v_refunded
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_refund_transactions_cap ON refund_transactions;
CREATE TRIGGER trg_refund_transactions_cap
    AFTER INSERT OR UPDATE OF amount, status, payment_transaction_id ON refund_transactions
    FOR EACH ROW EXECUTE FUNCTION enforce_refund_cap();

-- 6d. An owner payout never exceeds the payment that produced it.
CREATE OR REPLACE FUNCTION enforce_payout_cap()
RETURNS TRIGGER AS $$
DECLARE
    v_captured NUMERIC(12,2);
BEGIN
    IF NEW.triggering_payment_intent_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT amount INTO v_captured FROM payment_intents WHERE id = NEW.triggering_payment_intent_id;
    IF NEW.amount > v_captured THEN
        RAISE EXCEPTION 'PAYOUT_EXCEEDS_CAPTURE: payout amount % exceeds captured amount % of intent %',
            NEW.amount, v_captured, NEW.triggering_payment_intent_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_payouts_cap ON payouts;
CREATE TRIGGER trg_payouts_cap
    BEFORE INSERT OR UPDATE OF amount, triggering_payment_intent_id ON payouts
    FOR EACH ROW EXECUTE FUNCTION enforce_payout_cap();
