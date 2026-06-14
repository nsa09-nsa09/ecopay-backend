-- =========================================================
-- V21 — Track last successful login timestamp on each user, so the admin
-- panel can show "когда был онлайн". Updated by AuthService on both the
-- normal token-issuing path and the staff 2FA exchange. Existing rows stay
-- NULL until the first post-deploy login.
-- =========================================================

ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMP;
