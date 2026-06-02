-- =========================================================
-- V11 — Owner verification flag
-- Adds users.owner_verified so the catalog can surface a
-- "verified owner" badge. Backfilled from phone verification
-- (a phone-verified user is considered a verified owner for MVP;
--  an admin can later toggle this independently).
-- =========================================================

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS owner_verified BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE users
SET owner_verified = TRUE
WHERE phone_verified_at IS NOT NULL;
