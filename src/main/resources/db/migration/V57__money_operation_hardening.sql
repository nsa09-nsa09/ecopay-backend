ALTER TABLE payment_intents
    ADD COLUMN IF NOT EXISTS compensation_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS review_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS review_reason VARCHAR(100);

CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_transactions_one_charge_success
    ON payment_transactions(payment_intent_id)
    WHERE type = 'CHARGE' AND status = 'SUCCESS';

CREATE UNIQUE INDEX IF NOT EXISTS uq_payouts_triggering_payment_intent
    ON payouts(triggering_payment_intent_id)
    WHERE triggering_payment_intent_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_payouts_provider_payout_id
    ON payouts(provider_payout_id)
    WHERE provider_payout_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_refund_transactions_provider_refund_id
    ON refund_transactions(provider_refund_id)
    WHERE provider_refund_id IS NOT NULL;

ALTER TABLE freedom_webhook_inbox
    ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS last_error_code VARCHAR(80),
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_freedom_webhook_inbox_retry
    ON freedom_webhook_inbox(processing_status, next_retry_at, lease_until);

CREATE TABLE IF NOT EXISTS money_ledger_entries (
    id                      BIGSERIAL PRIMARY KEY,
    entry_type              VARCHAR(30) NOT NULL,
    amount                  NUMERIC(12,2) NOT NULL,
    currency                VARCHAR(10) NOT NULL DEFAULT 'KZT',
    direction               VARCHAR(10) NOT NULL,
    payment_intent_id       BIGINT REFERENCES payment_intents(id),
    payment_transaction_id  BIGINT REFERENCES payment_transactions(id),
    refund_transaction_id   BIGINT REFERENCES refund_transactions(id),
    payout_id               BIGINT REFERENCES payouts(id),
    owner_user_id           BIGINT REFERENCES users(id),
    idempotency_key         VARCHAR(150) NOT NULL,
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_money_ledger_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT chk_money_ledger_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_money_ledger_direction CHECK (direction IN ('DEBIT', 'CREDIT'))
);

CREATE INDEX IF NOT EXISTS idx_money_ledger_owner_created
    ON money_ledger_entries(owner_user_id, created_at);

CREATE INDEX IF NOT EXISTS idx_money_ledger_payment
    ON money_ledger_entries(payment_intent_id, entry_type);

CREATE OR REPLACE FUNCTION block_money_ledger_modify()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'money_ledger_entries is append-only';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_block_money_ledger_update ON money_ledger_entries;
DROP TRIGGER IF EXISTS trg_block_money_ledger_delete ON money_ledger_entries;

CREATE TRIGGER trg_block_money_ledger_update
    BEFORE UPDATE ON money_ledger_entries
    FOR EACH ROW EXECUTE FUNCTION block_money_ledger_modify();

CREATE TRIGGER trg_block_money_ledger_delete
    BEFORE DELETE ON money_ledger_entries
    FOR EACH ROW EXECUTE FUNCTION block_money_ledger_modify();
