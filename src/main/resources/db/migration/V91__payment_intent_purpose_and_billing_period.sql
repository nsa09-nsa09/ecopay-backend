-- B2 manual renewal: distinguish what a payment intent pays for and which billing period, and let a
-- lapsed membership be tracked without touching payout/refund logic. Additive and idempotent; works
-- on PostgreSQL 16 (CI Testcontainers) and CockroachDB (dev/prod). No PL/pgSQL, no triggers.

-- purpose: INITIAL (first payment), RENEWAL (manual next-period payment), RECURRING (auto-renewal).
-- Existing rows are all first payments, so the NOT NULL DEFAULT 'INITIAL' backfills them correctly.
ALTER TABLE payment_intents ADD COLUMN IF NOT EXISTS purpose VARCHAR(16) NOT NULL DEFAULT 'INITIAL';

-- For RENEWAL/RECURRING intents: the member's nextBillingAt of the period being paid, so a finalize
-- advances the period exactly once and a duplicate renewal for the same period is detectable.
ALTER TABLE payment_intents ADD COLUMN IF NOT EXISTS billing_period_start TIMESTAMP NULL;

-- Membership renewal lifecycle (grace + reminder bookkeeping). Decisions stay with the admin.
ALTER TABLE room_members ADD COLUMN IF NOT EXISTS renewal_overdue_since TIMESTAMP NULL;
ALTER TABLE room_members ADD COLUMN IF NOT EXISTS renewal_reminded_for TIMESTAMP NULL;

-- Look up "is there already an intent of this purpose for this member's period" cheaply.
CREATE INDEX IF NOT EXISTS idx_payment_intents_member_purpose_period
    ON payment_intents (room_member_id, purpose, billing_period_start);
