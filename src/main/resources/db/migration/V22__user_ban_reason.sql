-- =========================================================
-- V22 — Persist ban context on the user row so the support agent can show
-- the affected user *why* their account is blocked, and so the frontend can
-- render the same message on a forced logout via the WS account topic.
-- Filled by AdminUserController.ban(), cleared by unban().
-- =========================================================

ALTER TABLE users ADD COLUMN IF NOT EXISTS ban_reason TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS banned_at TIMESTAMP;
