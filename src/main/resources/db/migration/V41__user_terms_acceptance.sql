-- =========================================================
-- V41 — Track the user's acceptance of the legal documents at registration.
--
-- All three columns are nullable so existing users predating this migration
-- keep working; new registrations fill them via AuthService.register.
-- =========================================================

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS terms_accepted_at        TIMESTAMP,
    ADD COLUMN IF NOT EXISTS accepted_terms_version   INTEGER,
    ADD COLUMN IF NOT EXISTS accepted_privacy_version INTEGER;
