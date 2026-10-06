-- =========================================================
-- V52 — Email becomes optional; registration can be phone-based.
--
--   * users.email is nullable: accounts created via phone + OTP have no
--     email until the user adds one in their profile. Uniqueness is kept
--     (Postgres UNIQUE ignores NULLs, so any number of email-less rows
--     coexist).
--   * email_verification_tokens.pending_email: when a user adds or changes
--     their email, the new address lives here until the emailed code is
--     confirmed. The users.email column is only touched after confirmation,
--     so a typo'd or abandoned change never locks the account out and the
--     old address keeps working meanwhile.
-- =========================================================

ALTER TABLE users ALTER COLUMN email DROP NOT NULL;

ALTER TABLE email_verification_tokens
    ADD COLUMN IF NOT EXISTS pending_email VARCHAR(255);
