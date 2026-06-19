-- Phase 38: Owner payout-card binding via the Freedom Pay hosted page.
-- A short-lived verification charge (auto-refunded) tokenizes the owner's card so it
-- can be saved and registered as a payout method — no manual token entry. This table
-- tracks each binding attempt and ties the resulting Freedom Pay payment id to the user
-- (so confirmation can't be hijacked to claim another user's card token).

CREATE TABLE IF NOT EXISTS payout_card_bindings (
    id                  BIGSERIAL    PRIMARY KEY,
    user_id             BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider_name       VARCHAR(50)  NOT NULL,
    external_payment_id VARCHAR(150),
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    amount              NUMERIC(12,2) NOT NULL,
    currency            VARCHAR(10)  NOT NULL DEFAULT 'KZT',
    idempotency_key     VARCHAR(100) NOT NULL UNIQUE,
    pan_mask            VARCHAR(20),
    payout_method_id    BIGINT       REFERENCES payout_methods(id),
    failure_message     TEXT,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at        TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_payout_card_bindings_user_status
    ON payout_card_bindings (user_id, status);
