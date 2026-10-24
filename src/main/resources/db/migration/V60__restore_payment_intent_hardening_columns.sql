-- V57 was already recorded in some shared dev databases before these
-- PaymentIntent hardening columns landed. Keep this repair idempotent so the
-- current entity model and the real schema converge without editing history.

ALTER TABLE payment_intents
    ADD COLUMN IF NOT EXISTS compensation_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS review_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS review_reason VARCHAR(100);
