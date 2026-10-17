-- =========================================================
-- V17 — Add users.public_id (URL-safe hash for public profile),
-- users.deleted_at (soft-delete timestamp), and extend the
-- chk_users_status CHECK to allow the DELETED status used by
-- account-deletion anonymization.
-- =========================================================

-- 1) New columns. public_id is added nullable so the backfill can populate it
--    on existing rows before we flip it to NOT NULL.
ALTER TABLE users ADD COLUMN IF NOT EXISTS public_id VARCHAR(16);
ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;

-- 2) Backfill: 12-char token derived from a random UUID (hex, no dashes).
--    CockroachDB has gen_random_uuid() built in and does not support anonymous
--    DO blocks, so this is a plain set-based UPDATE instead of a PL/pgSQL loop.
UPDATE users
SET public_id = substr(replace(gen_random_uuid()::text, '-', ''), 1, 12)
WHERE public_id IS NULL;

-- 3) Lock the column down and enforce uniqueness.
ALTER TABLE users ALTER COLUMN public_id SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT uk_users_public_id UNIQUE (public_id);

-- 4) Extend chk_users_status to allow DELETED (used during account
--    anonymization; see UserStatus.DELETED).
ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_status;
ALTER TABLE users
    ADD CONSTRAINT chk_users_status
    CHECK (status IN ('ACTIVE', 'BANNED', 'DELETED'));
