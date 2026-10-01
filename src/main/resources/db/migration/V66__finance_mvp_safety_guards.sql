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
        'CAPTURE_ANOMALY',
        'FAILED',
        'CANCELLED'
    ));

ALTER TABLE refund_transactions
    ADD COLUMN IF NOT EXISTS retry_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMP,
    ADD COLUMN IF NOT EXISTS last_error_code VARCHAR(80),
    ADD COLUMN IF NOT EXISTS last_error_message TEXT;

CREATE INDEX IF NOT EXISTS idx_refund_transactions_dispatch
    ON refund_transactions(status, next_retry_at, lease_until)
    WHERE status IN ('PENDING', 'FAILED');
