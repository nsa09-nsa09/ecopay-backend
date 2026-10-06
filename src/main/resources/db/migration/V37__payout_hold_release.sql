-- Phase 37: Hold owner payouts for a fixed period (default 30 days) before dispatch.
-- The member's charge is still captured immediately into the merchant balance; only
-- the payout to the room owner is delayed. release_at gates the payout dispatcher.
-- (Renumbered from V33 (versions 33-36 were already taken by other migrations on the shared DB) — version 33 was already taken by another migration on the
-- shared database; see V33 for that change.)

ALTER TABLE payouts
    ADD COLUMN IF NOT EXISTS release_at TIMESTAMP;

-- Backfill existing payouts: they were created under the old immediate-dispatch model,
-- so make them eligible right away (release_at = created_at) and never strand them.
UPDATE payouts
    SET release_at = created_at
    WHERE release_at IS NULL;

-- Dispatcher polls by (status, release_at): "give me dispatchable payouts due now".
CREATE INDEX IF NOT EXISTS idx_payouts_status_release
    ON payouts (status, release_at);
