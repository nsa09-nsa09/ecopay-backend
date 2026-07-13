-- Email verification via 6-digit code (mandatory for regular USER sign-up).
-- The link-based token stays in place; we add a hashed short code + attempt
-- counter so the same row can be confirmed with the code sent to the inbox.
-- Only the hashed code is stored, never the plaintext.

ALTER TABLE email_verification_tokens
    ADD COLUMN IF NOT EXISTS code_hash VARCHAR(255),
    ADD COLUMN IF NOT EXISTS attempts  INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_email_verification_user
    ON email_verification_tokens (user_id);
