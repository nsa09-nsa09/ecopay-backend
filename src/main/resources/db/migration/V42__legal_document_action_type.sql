-- Fix: re-declare the admin_action_log.action_type CHECK constraint so that it
-- permits LEGAL_DOCUMENT_UPDATED. Postgres requires the FULL value list on every
-- redefinition of a CHECK constraint; the list below is copied verbatim from
-- V34__news.sql (the previous authoritative definition) with the single new
-- value appended at the end. Dropping any existing value here would break audit
-- logging for that action type.
--
-- Idempotent alongside V40: V40 already redeclared the constraint with the same
-- list, so this migration is a safety net for environments where V40 was
-- applied before LEGAL_DOCUMENT_UPDATED was appended (or hand-patched away).

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
        'LEGAL_DOCUMENT_UPDATED'
    ));
