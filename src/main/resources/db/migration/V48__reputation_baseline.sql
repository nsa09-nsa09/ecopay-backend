-- =========================================================
-- V48 — Reputation switches to the "trust" model:
--   * New baseline for fresh users is 100/100 (was 0).
--   * Existing users are reset to 100 — recompute()  will re-derive
--     the real value from reviews and confirmed violations on next
--     recompute call.
-- Column type stays INTEGER (0..100), only the DEFAULT and initial
-- values change.
-- =========================================================

ALTER TABLE users ALTER COLUMN reputation SET DEFAULT 100;

UPDATE users SET reputation = 100 WHERE deleted_at IS NULL;
