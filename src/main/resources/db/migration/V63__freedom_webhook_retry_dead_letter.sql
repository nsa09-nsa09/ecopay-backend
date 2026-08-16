ALTER TABLE freedom_webhook_inbox
    ADD COLUMN IF NOT EXISTS callback_script VARCHAR(30) NOT NULL DEFAULT 'result',
    ADD COLUMN IF NOT EXISTS lease_owner VARCHAR(100),
    ADD COLUMN IF NOT EXISTS last_attempt_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS dead_lettered_at TIMESTAMP;

UPDATE freedom_webhook_inbox
SET callback_script = 'payout-result'
WHERE raw_body ->> 'pg_payout_id' IS NOT NULL
   OR raw_body ->> 'pg_event_type' = 'PAYOUT';

ALTER TABLE freedom_webhook_inbox
    DROP CONSTRAINT IF EXISTS chk_freedom_webhook_inbox_status;

ALTER TABLE freedom_webhook_inbox
    ADD CONSTRAINT chk_freedom_webhook_inbox_status
    CHECK (processing_status IN ('PENDING', 'PROCESSING', 'FAILED', 'PROCESSED', 'DEAD_LETTER'));

CREATE INDEX IF NOT EXISTS idx_freedom_webhook_inbox_due
    ON freedom_webhook_inbox (processing_status, next_retry_at, lease_until, received_at);
