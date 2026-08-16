ALTER TABLE service_reviews
    ADD COLUMN IF NOT EXISTS featured_position INTEGER;

ALTER TABLE service_reviews
    DROP CONSTRAINT IF EXISTS chk_service_reviews_featured_position;

ALTER TABLE service_reviews
    ADD CONSTRAINT chk_service_reviews_featured_position
    CHECK (featured_position IS NULL OR featured_position BETWEEN 1 AND 6);

CREATE UNIQUE INDEX IF NOT EXISTS uq_service_reviews_featured_position
    ON service_reviews(featured_position)
    WHERE featured_position IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_service_reviews_homepage_position
    ON service_reviews(featured, featured_position);

ALTER TABLE admin_action_log
    DROP CONSTRAINT IF EXISTS chk_admin_action_log_action_type;

ALTER TABLE admin_action_log
    ADD CONSTRAINT chk_admin_action_log_action_type
    CHECK (action_type IN (
        'ACCESS_CONFIRMED',
        'ACCESS_REJECTED',
        'ROOM_BLOCKED',
        'ROOM_UNBLOCKED',
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
        'TESTIMONIAL_REORDERED',
        'TESTIMONIAL_EDITED',
        'TESTIMONIAL_DELETED',
        'SITE_CONTENT_UPDATED',
        'FEEDBACK_STATUS_CHANGED',
        'FEEDBACK_NOTE_UPDATED',
        'NEWS_CREATED',
        'NEWS_UPDATED',
        'NEWS_DELETED',
        'LEGAL_DOCUMENT_UPDATED',
        'STORY_CREATED',
        'STORY_UPDATED',
        'STORY_DELETED'
    ));
