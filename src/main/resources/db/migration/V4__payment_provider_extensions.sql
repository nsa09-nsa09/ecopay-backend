-- Phase 3: Payment provider integration (Freedom Pay sandbox).
-- Extends existing payment tables with provider-specific fields and
-- adds new tables for saved cards, webhook idempotency and event log.

ALTER TABLE payment_intents
    ADD COLUMN IF NOT EXISTS payment_url TEXT,
    ADD COLUMN IF NOT EXISTS save_card_requested BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS saved_card_id BIGINT,
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS last_webhook_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS provider_status_code VARCHAR(50),
    ADD COLUMN IF NOT EXISTS failure_code VARCHAR(50),
    ADD COLUMN IF NOT EXISTS failure_message TEXT;

ALTER TABLE payment_transactions
    ADD COLUMN IF NOT EXISTS provider_signature TEXT,
    ADD COLUMN IF NOT EXISTS provider_status_code VARCHAR(50),
    ADD COLUMN IF NOT EXISTS failure_code VARCHAR(50),
    ADD COLUMN IF NOT EXISTS failure_message TEXT,
    ADD COLUMN IF NOT EXISTS card_pan_mask VARCHAR(20);

CREATE TABLE IF NOT EXISTS saved_cards (
    id              BIGSERIAL    PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider_name   VARCHAR(50)  NOT NULL,
    provider_token  VARCHAR(255) NOT NULL,
    pan_mask        VARCHAR(20),
    card_type       VARCHAR(20),
    expiry_month    INT,
    expiry_year     INT,
    is_default      BOOLEAN      NOT NULL DEFAULT FALSE,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at      TIMESTAMP,
    CONSTRAINT uq_saved_cards_user_token UNIQUE (user_id, provider_token)
);

CREATE INDEX IF NOT EXISTS idx_saved_cards_user_status
    ON saved_cards (user_id, status);

CREATE UNIQUE INDEX IF NOT EXISTS uq_saved_cards_user_default
    ON saved_cards (user_id) WHERE is_default = TRUE AND status = 'ACTIVE';

-- Late FK from payment_intents to saved_cards (column was added above without ref).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_payment_intents_saved_card'
    ) THEN
        ALTER TABLE payment_intents
            ADD CONSTRAINT fk_payment_intents_saved_card
            FOREIGN KEY (saved_card_id) REFERENCES saved_cards(id) ON DELETE SET NULL;
    END IF;
END $$;

-- Webhook inbox guarantees idempotency: every Freedom Pay callback
-- is recorded once and only once.
CREATE TABLE IF NOT EXISTS freedom_webhook_inbox (
    id                  BIGSERIAL    PRIMARY KEY,
    provider_request_id VARCHAR(200) NOT NULL UNIQUE,
    raw_body            JSONB        NOT NULL,
    signature_valid     BOOLEAN,
    received_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at        TIMESTAMP,
    processing_status   VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    error_message       TEXT
);

CREATE INDEX IF NOT EXISTS idx_freedom_webhook_inbox_status
    ON freedom_webhook_inbox (processing_status, received_at);

-- Append-only payment event log. Triggers prevent UPDATE/DELETE.
CREATE TABLE IF NOT EXISTS payment_event_log (
    id              BIGSERIAL    PRIMARY KEY,
    entity_type     VARCHAR(30)  NOT NULL,
    entity_id       BIGINT       NOT NULL,
    event_type      VARCHAR(50)  NOT NULL,
    from_status     VARCHAR(50),
    to_status       VARCHAR(50),
    actor_user_id   BIGINT,
    correlation_id  VARCHAR(100),
    idempotency_key VARCHAR(100),
    payload         JSONB,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_payment_event_log_entity
    ON payment_event_log (entity_type, entity_id, created_at);

CREATE OR REPLACE FUNCTION block_payment_event_log_modify()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'payment_event_log is append-only';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_block_pel_update ON payment_event_log;
DROP TRIGGER IF EXISTS trg_block_pel_delete ON payment_event_log;

CREATE TRIGGER trg_block_pel_update
    BEFORE UPDATE ON payment_event_log
    FOR EACH ROW EXECUTE FUNCTION block_payment_event_log_modify();

CREATE TRIGGER trg_block_pel_delete
    BEFORE DELETE ON payment_event_log
    FOR EACH ROW EXECUTE FUNCTION block_payment_event_log_modify();
