-- Phase 6: Payouts to room owners via Freedom Pay payouts API.

CREATE TABLE IF NOT EXISTS payout_methods (
    id                  BIGSERIAL    PRIMARY KEY,
    user_id             BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider_name       VARCHAR(50)  NOT NULL,
    provider_card_token VARCHAR(255) NOT NULL,
    pan_mask            VARCHAR(20),
    verified_at         TIMESTAMP,
    is_default          BOOLEAN      NOT NULL DEFAULT FALSE,
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at          TIMESTAMP,
    CONSTRAINT uq_payout_methods_user_token UNIQUE (user_id, provider_card_token)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_payout_methods_user_default
    ON payout_methods (user_id) WHERE is_default = TRUE AND status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS payouts (
    id                              BIGSERIAL    PRIMARY KEY,
    user_id                         BIGINT       NOT NULL REFERENCES users(id),
    room_id                         BIGINT       REFERENCES rooms(id),
    payout_method_id                BIGINT       REFERENCES payout_methods(id),
    amount                          NUMERIC(12,2) NOT NULL,
    currency                        VARCHAR(10)  NOT NULL DEFAULT 'KZT',
    status                          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    provider_payout_id              VARCHAR(150),
    idempotency_key                 VARCHAR(100) NOT NULL UNIQUE,
    triggering_payment_intent_id    BIGINT       REFERENCES payment_intents(id),
    failure_reason                  TEXT,
    retry_count                     INT          NOT NULL DEFAULT 0,
    created_at                      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at                    TIMESTAMP,
    updated_at                      TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_payouts_user_status
    ON payouts (user_id, status);

CREATE INDEX IF NOT EXISTS idx_payouts_status_created
    ON payouts (status, created_at);
