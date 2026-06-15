-- =========================================================
-- V28 — User feedback inbox (complaints / ideas / requests).
--
-- A single funnel for unstructured user input that doesn't fit Support tickets
-- (no SLA, no dispute escalation). Each row is owned by exactly one user; the
-- admin panel triages by status. We log every admin status/note change to
-- admin_action_log (see V28 CHECK constraint extension below) so the audit
-- trail covers feedback the same way it covers catalog edits.
-- =========================================================

CREATE TABLE IF NOT EXISTS feedback (
    id           BIGSERIAL    PRIMARY KEY,
    type         VARCHAR(20)  NOT NULL,
    subject      VARCHAR(150),
    message      TEXT         NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'NEW',
    user_id      BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    admin_note   TEXT,
    handled_by   BIGINT       REFERENCES users(id) ON DELETE SET NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP,

    CONSTRAINT chk_feedback_type   CHECK (type   IN ('COMPLAINT', 'IDEA', 'REQUEST')),
    CONSTRAINT chk_feedback_status CHECK (status IN ('NEW', 'IN_REVIEW', 'RESOLVED', 'DISMISSED'))
);

-- Admin triage queries filter by (status, created_at); user-side queries hit
-- (user_id, created_at). Both warrant indexes.
CREATE INDEX IF NOT EXISTS idx_feedback_status_created_at
    ON feedback (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_feedback_user_created_at
    ON feedback (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_feedback_type
    ON feedback (type);

-- Extend the append-only admin_action_log CHECK constraint to allow the new
-- audit action types. Mirrors the V24 migration's drop-and-recreate pattern.
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
        'FEEDBACK_NOTE_UPDATED'
    ));
