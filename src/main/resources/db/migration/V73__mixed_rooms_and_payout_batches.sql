ALTER TABLE rooms
    ADD COLUMN IF NOT EXISTS existing_members_count INTEGER NOT NULL DEFAULT 1;

ALTER TABLE rooms
    DROP CONSTRAINT IF EXISTS chk_rooms_existing_members_count;

ALTER TABLE rooms
    ADD CONSTRAINT chk_rooms_existing_members_count
    CHECK (existing_members_count >= 1 AND existing_members_count < max_members);

CREATE TABLE IF NOT EXISTS payout_batches (
    id                  BIGSERIAL PRIMARY KEY,
    owner_user_id       BIGINT NOT NULL REFERENCES users(id),
    payout_method_id    BIGINT NOT NULL REFERENCES payout_methods(id),
    currency            VARCHAR(10) NOT NULL DEFAULT 'KZT',
    amount              NUMERIC(12,2) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
    provider_payout_id  VARCHAR(150),
    idempotency_key     VARCHAR(100) NOT NULL,
    retry_count         INTEGER NOT NULL DEFAULT 0,
    failure_reason      TEXT,
    next_retry_at       TIMESTAMP,
    lease_until         TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at        TIMESTAMP,
    updated_at          TIMESTAMP,

    CONSTRAINT chk_payout_batches_status
    CHECK (status IN ('PROCESSING','PENDING_PROVIDER','SUCCESS','FAILED','REQUIRES_REVIEW')),

    CONSTRAINT chk_payout_batches_amount
    CHECK (amount > 0),

    CONSTRAINT uq_payout_batches_idempotency UNIQUE (idempotency_key)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_payout_batches_provider_id
    ON payout_batches(provider_payout_id)
    WHERE provider_payout_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_payout_batches_status_retry
    ON payout_batches(status, next_retry_at);

CREATE INDEX IF NOT EXISTS idx_payout_batches_owner_status
    ON payout_batches(owner_user_id, status);

ALTER TABLE payouts
    ADD COLUMN IF NOT EXISTS payout_batch_id BIGINT REFERENCES payout_batches(id);

CREATE INDEX IF NOT EXISTS idx_payouts_batch
    ON payouts(payout_batch_id)
    WHERE payout_batch_id IS NOT NULL;
