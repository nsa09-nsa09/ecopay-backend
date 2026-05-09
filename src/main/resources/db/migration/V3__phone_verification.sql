-- Phase 1: Phone verification (SMS).
-- Adds phone fields on users (nullable until verified) and a one-time-code table.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS phone VARCHAR(20),
    ADD COLUMN IF NOT EXISTS phone_verified_at TIMESTAMP;

-- Phone is unique only when present (allow many NULLs).
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_phone
    ON users (phone)
    WHERE phone IS NOT NULL;

CREATE TABLE IF NOT EXISTS phone_verifications (
    id           BIGSERIAL    PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    phone        VARCHAR(20)  NOT NULL,
    code_hash    VARCHAR(255) NOT NULL,
    attempts     INT          NOT NULL DEFAULT 0,
    expires_at   TIMESTAMP    NOT NULL,
    verified_at  TIMESTAMP,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_phone_verifications_user_phone
    ON phone_verifications (user_id, phone);

CREATE INDEX IF NOT EXISTS idx_phone_verifications_phone_created
    ON phone_verifications (phone, created_at);
