-- Fix: re-declare the admin_action_log.action_type CHECK constraint so that it
-- permits ROOM_UNBLOCKED. Postgres requires the FULL value list on every
-- redefinition of a CHECK constraint; the list below is copied verbatim from
-- V42__fix_admin_action_log_legal_document.sql (the previous authoritative
-- definition) with the single new value appended at the end. Dropping any
-- existing value here would break audit logging for that action type.

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
        'ROOM_UNBLOCKED'
    ));
