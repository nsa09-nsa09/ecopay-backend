-- =========================================================
-- V32 — In-app + email notification system
--
-- Two tables:
--   • notifications              — one row per delivered in-app notification.
--   • notification_preferences   — per (user, type) channel opt-out. A missing
--                                  row means "all channels on" (lazy default),
--                                  so we never have to back-fill on user create.
--
-- The `metadata` jsonb carries the structured context the frontend needs to
-- render/deep-link without another round-trip (roomId, memberId, amount, ...).
-- =========================================================

CREATE TABLE notifications (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type        VARCHAR(50) NOT NULL,
    title       VARCHAR(200) NOT NULL,
    body        TEXT        NOT NULL,
    link        VARCHAR(300),
    metadata    JSONB,
    read_at     TIMESTAMP,
    created_at  TIMESTAMP   NOT NULL DEFAULT now()
);

-- Drives the two hot queries: the paginated bell list (user_id + created_at)
-- and the unread badge count (user_id + read_at IS NULL).
CREATE INDEX idx_notifications_user_created
    ON notifications (user_id, created_at DESC);

CREATE INDEX idx_notifications_user_unread
    ON notifications (user_id)
    WHERE read_at IS NULL;

CREATE TABLE notification_preferences (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type        VARCHAR(50) NOT NULL,
    in_app      BOOLEAN     NOT NULL DEFAULT TRUE,
    email       BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at  TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_pref_user_type UNIQUE (user_id, type)
);

CREATE INDEX idx_notification_prefs_user
    ON notification_preferences (user_id);
