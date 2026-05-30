-- =========================================================
-- Email verification
-- V3
-- =========================================================

-- Flag on users. New registrations default to FALSE and must verify.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT FALSE;

-- Grandfather every pre-existing account as verified so current users keep access.
UPDATE users
SET email_verified = TRUE
WHERE email_verified = FALSE;

CREATE TABLE IF NOT EXISTS email_verification_tokens (
    id              BIGSERIAL PRIMARY KEY,
    token           VARCHAR(255) NOT NULL UNIQUE,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at      TIMESTAMP NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    used            BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_verification_token ON email_verification_tokens(token);
