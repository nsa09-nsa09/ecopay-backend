ALTER TABLE payment_intents
    ADD COLUMN IF NOT EXISTS captured_at TIMESTAMP;

ALTER TABLE payment_transactions
    ADD COLUMN IF NOT EXISTS captured_at TIMESTAMP;

UPDATE payment_transactions
SET captured_at = created_at
WHERE captured_at IS NULL AND type = 'CHARGE' AND status IN ('SUCCESS','REFUNDED_PARTIAL','REFUNDED_FULL');

UPDATE payment_intents pi
SET captured_at = (
    SELECT MIN(pt.captured_at)
    FROM payment_transactions pt
    WHERE pt.payment_intent_id = pi.id
      AND pt.type = 'CHARGE'
      AND pt.captured_at IS NOT NULL
)
WHERE pi.captured_at IS NULL;

ALTER TABLE payouts
    ADD COLUMN IF NOT EXISTS original_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS refunded_share_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS payable_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS captured_at TIMESTAMP;

UPDATE payouts
SET original_amount = amount,
    payable_amount = amount
WHERE original_amount = 0 AND payable_amount = 0;

UPDATE payouts p
SET captured_at = COALESCE(
    (
        SELECT MIN(pt.captured_at)
        FROM payment_transactions pt
        WHERE pt.payment_intent_id = p.triggering_payment_intent_id
          AND pt.type = 'CHARGE'
          AND pt.captured_at IS NOT NULL
    ),
    p.created_at
)
WHERE p.captured_at IS NULL;

-- All legacy unpaid payouts were frozen by V68. Recalculate their contractual boundary from the
-- best capture timestamp available, but do not unfreeze them automatically.
UPDATE payouts
SET release_at = captured_at + INTERVAL '30 days'
WHERE captured_at IS NOT NULL AND status = 'FROZEN';
