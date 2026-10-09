CREATE TABLE IF NOT EXISTS payment_reservations (
    id                  BIGSERIAL PRIMARY KEY,
    payment_intent_id   BIGINT NOT NULL REFERENCES payment_intents(id),
    room_member_id      BIGINT NOT NULL REFERENCES room_members(id),
    room_id             BIGINT NOT NULL REFERENCES rooms(id),
    status              VARCHAR(20) NOT NULL DEFAULT 'RESERVED',
    expires_at          TIMESTAMP NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    consumed_at         TIMESTAMP,
    released_at         TIMESTAMP,
    release_reason      VARCHAR(80),
    CONSTRAINT uq_payment_reservations_intent UNIQUE (payment_intent_id),
    CONSTRAINT chk_payment_reservations_status CHECK (status IN ('RESERVED','CONSUMED','RELEASED'))
);

CREATE INDEX IF NOT EXISTS idx_payment_reservations_room_status_expires
    ON payment_reservations(room_id, status, expires_at);

CREATE INDEX IF NOT EXISTS idx_payment_reservations_member_status
    ON payment_reservations(room_member_id, status);

UPDATE support_tickets
SET updated_at = created_at
WHERE updated_at IS NULL;

ALTER TABLE payouts
    ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_payouts_dispatch_due
    ON payouts(status, release_at, next_retry_at, lease_until);
