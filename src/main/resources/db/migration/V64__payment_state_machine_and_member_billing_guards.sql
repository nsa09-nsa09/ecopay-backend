ALTER TABLE payment_intents
    DROP CONSTRAINT IF EXISTS chk_payment_intents_status;

ALTER TABLE payment_intents
    ADD CONSTRAINT chk_payment_intents_status
    CHECK (status IN (
        'PENDING',
        'UNKNOWN',
        'RECONCILING',
        'SUCCESS',
        'EXPIRED',
        'REFUND_REQUIRED',
        'REFUND_PENDING',
        'REFUNDED',
        'REQUIRES_REVIEW',
        'FAILED',
        'CANCELLED'
    ));

ALTER TABLE refund_transactions
    DROP CONSTRAINT IF EXISTS chk_refund_transactions_status;

ALTER TABLE refund_transactions
    ADD CONSTRAINT chk_refund_transactions_status
    CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'REQUIRES_REVIEW'));

ALTER TABLE room_members
    ADD COLUMN IF NOT EXISTS billing_anchor_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS billing_period_start TIMESTAMP,
    ADD COLUMN IF NOT EXISTS next_billing_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS recurring_retry_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS recurring_next_retry_at TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_intents_open_per_room_member
    ON payment_intents(room_member_id)
    WHERE status IN ('PENDING', 'UNKNOWN', 'RECONCILING');

CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_reservations_reserved_per_room_member
    ON payment_reservations(room_member_id)
    WHERE status = 'RESERVED';

CREATE INDEX IF NOT EXISTS idx_room_members_next_billing
    ON room_members(status, next_billing_at, recurring_next_retry_at)
    WHERE deleted_at IS NULL;
