-- =========================================================
-- V17 — Add users.public_id (URL-safe hash for public profile),
-- users.deleted_at (soft-delete timestamp), and extend the
-- chk_users_status CHECK to allow the DELETED status used by
-- account-deletion anonymization.
-- =========================================================

-- Need gen_random_bytes() for the backfill (pgcrypto).
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 1) New columns. public_id is added nullable + UNIQUE so the backfill
--    can populate it on existing rows before we flip it to NOT NULL.
ALTER TABLE users ADD COLUMN IF NOT EXISTS public_id VARCHAR(16);
ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;

-- 2) Backfill: deterministic-length 12-char URL-safe token derived from
--    16 random bytes (base64url, stripped to 12 chars). The retry loop
--    handles any (astronomically unlikely) collisions deterministically.
DO $$
DECLARE
    candidate VARCHAR(16);
    target_id BIGINT;
BEGIN
    FOR target_id IN SELECT id FROM users WHERE public_id IS NULL LOOP
        LOOP
            candidate := substr(
                translate(encode(gen_random_bytes(16), 'base64'),
                          '+/=', '-_'),
                1, 12);
            EXIT WHEN NOT EXISTS (
                SELECT 1 FROM users WHERE public_id = candidate
            );
        END LOOP;
        UPDATE users SET public_id = candidate WHERE id = target_id;
    END LOOP;
END$$;

-- 3) Lock the column down.
ALTER TABLE users ALTER COLUMN public_id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE schemaname = current_schema()
          AND indexname = 'uk_users_public_id'
    ) THEN
        EXECUTE 'ALTER TABLE users ADD CONSTRAINT uk_users_public_id UNIQUE (public_id)';
    END IF;
END$$;

-- 4) Extend chk_users_status to allow DELETED (used during account
--    anonymization; see UserStatus.DELETED).
ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_status;
ALTER TABLE users
    ADD CONSTRAINT chk_users_status
    CHECK (status IN ('ACTIVE', 'BANNED', 'DELETED'));
