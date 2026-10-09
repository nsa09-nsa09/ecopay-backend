CREATE TABLE IF NOT EXISTS stories (
    id              BIGSERIAL    PRIMARY KEY,

    title_kz        TEXT,
    title_ru        TEXT,
    title_en        TEXT,

    heading_kz      TEXT,
    heading_ru      TEXT,
    heading_en      TEXT,

    body_kz         TEXT,
    body_ru         TEXT,
    body_en         TEXT,

    cta_label_kz    VARCHAR(120),
    cta_label_ru    VARCHAR(120),
    cta_label_en    VARCHAR(120),
    cta_url         VARCHAR(500),

    emoji           VARCHAR(32),
    gradient        VARCHAR(255),
    image_key       VARCHAR(255),

    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    published_at    TIMESTAMP,
    sort_order      INTEGER      NOT NULL DEFAULT 0,

    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP,
    created_by      BIGINT       REFERENCES users(id) ON DELETE SET NULL,

    CONSTRAINT chk_stories_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED'))
);

CREATE INDEX IF NOT EXISTS idx_stories_status_sort
    ON stories (status, sort_order, published_at DESC);

CREATE INDEX IF NOT EXISTS idx_stories_created_at
    ON stories (created_at DESC);

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
        'NEWS_DELETED',
        'LEGAL_DOCUMENT_UPDATED',
        'ROOM_UNBLOCKED',
        'STORY_CREATED',
        'STORY_UPDATED',
        'STORY_DELETED'
    ));
