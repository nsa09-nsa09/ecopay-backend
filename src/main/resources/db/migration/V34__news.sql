-- =========================================================
-- V32 — Tri-lingual editorial news module.
--
-- Admin-managed news feed surfaced on the public landing page. Triple-locale
-- copy (kk / ru / en) mirrors the V30 site_content layout, an optional image
-- pointer (image_key) is just an S3 object key — the bytes themselves live in
-- the bucket and are streamed through the backend (same pattern as avatars).
-- Public reads filter to PUBLISHED status; the admin panel sees every status.
--
-- Indexes target the two hot paths:
--   1) Public feed: WHERE status='PUBLISHED' ORDER BY sort_order, published_at DESC.
--   2) Admin list:  ORDER BY created_at DESC, optionally filtered by status.
--
-- We also extend the append-only admin_action_log CHECK constraint with the
-- three new audit action types so writes from the news admin endpoints can land
-- without violating the constraint. The legacy values are preserved verbatim.
-- =========================================================

CREATE TABLE IF NOT EXISTS news (
    id            BIGSERIAL    PRIMARY KEY,

    title_kz      TEXT,
    title_ru      TEXT,
    title_en      TEXT,

    body_kz       TEXT,
    body_ru       TEXT,
    body_en       TEXT,

    image_key     VARCHAR(255),

    status        VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    published_at  TIMESTAMP,
    sort_order    INTEGER      NOT NULL DEFAULT 0,

    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP,
    created_by    BIGINT       REFERENCES users(id) ON DELETE SET NULL,

    CONSTRAINT chk_news_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED'))
);

-- Hot path for the public feed (status filter + the canonical ordering).
CREATE INDEX IF NOT EXISTS idx_news_status_published_at
    ON news (status, published_at DESC);

-- Admin list / sort by sort_order then newest.
CREATE INDEX IF NOT EXISTS idx_news_sort_created_at
    ON news (sort_order, created_at DESC);

-- Extend the append-only admin_action_log CHECK constraint to allow the new
-- audit action types. Mirrors V28's drop-and-recreate pattern.
ALTER TABLE admin_action_log
    DROP CONSTRAINT IF EXISTS chk_admin_action_log_action_type;

ALTER TABLE admin_action_log
    ADD CONSTRAINT chk_admin_action_log_action_type
    CHECK (action_type IN (
        'ACCESS_CONFIRMED',
        'ACCESS_REJECTED',
        'ROOM_BLOCKED',
        'USER_BANNED',
        'USER_UNBANNED',
        'USER_CREATED',
        'USER_ROLE_CHANGED',
        'REFUND_INITIATED',
        'REFUND_APPROVED',
        'REFUND_REJECTED',
        'DISPUTE_RESOLVED',
        'BATCH_CONFIRM',
        'OWNER_VERIFICATION_CHANGED',
        'CATEGORY_CREATED',
        'CATEGORY_UPDATED',
        'CATEGORY_DELETED',
        'SERVICE_CREATED',
        'SERVICE_UPDATED',
        'SERVICE_DELETED',
        'TARIFF_CREATED',
        'TARIFF_UPDATED',
        'TARIFF_DELETED',
        'TESTIMONIAL_FEATURED',
        'TESTIMONIAL_UNFEATURED',
        'TESTIMONIAL_EDITED',
        'TESTIMONIAL_DELETED',
        'SITE_CONTENT_UPDATED',
        'FEEDBACK_STATUS_CHANGED',
        'FEEDBACK_NOTE_UPDATED',
        'NEWS_CREATED',
        'NEWS_UPDATED',
        'NEWS_DELETED'
    ));
