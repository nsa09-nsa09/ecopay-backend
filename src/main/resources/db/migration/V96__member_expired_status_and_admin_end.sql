-- C4: a terminal state for an ACTIVE membership that an admin closed because the member missed
-- renewal past the grace window. Separate from CANCELLED_BEFORE_PAYMENT (no money changed hands)
-- and from ROOM_CANCELLED (the whole room ended). The admin never cancels during the happy path;
-- this status covers only the "renewal lapsed, admin decided to end participation" case.

ALTER TABLE room_members
    DROP CONSTRAINT IF EXISTS chk_room_members_status;

ALTER TABLE room_members
    ADD CONSTRAINT chk_room_members_status
    CHECK (status IN (
           'APPLIED',
           'PENDING',
           'ACTIVE',
           'REJECTED',
           'CANCELLED_BEFORE_PAYMENT',
           'ROOM_CANCELLED',
           'BLOCKED_BY_ADMIN',
           'EXPIRED'
                     ));

-- Widen the admin_action_log check so the new MEMBER_EXPIRED entry can be recorded.
ALTER TABLE admin_action_log DROP CONSTRAINT IF EXISTS chk_admin_action_log_action_type;
ALTER TABLE admin_action_log ADD CONSTRAINT chk_admin_action_log_action_type CHECK (
    ((action_type)::text = ANY (ARRAY[
        ('ACCESS_CONFIRMED'::character varying)::text,
        ('ACCESS_REJECTED'::character varying)::text,
        ('ROOM_BLOCKED'::character varying)::text,
        ('ROOM_UNBLOCKED'::character varying)::text,
        ('USER_BANNED'::character varying)::text,
        ('USER_UNBANNED'::character varying)::text,
        ('USER_CREATED'::character varying)::text,
        ('USER_ROLE_CHANGED'::character varying)::text,
        ('REFUND_INITIATED'::character varying)::text,
        ('REFUND_APPROVED'::character varying)::text,
        ('REFUND_REJECTED'::character varying)::text,
        ('DISPUTE_RESOLVED'::character varying)::text,
        ('BATCH_CONFIRM'::character varying)::text,
        ('OWNER_VERIFICATION_CHANGED'::character varying)::text,
        ('CATEGORY_CREATED'::character varying)::text,
        ('CATEGORY_UPDATED'::character varying)::text,
        ('CATEGORY_DELETED'::character varying)::text,
        ('SERVICE_CREATED'::character varying)::text,
        ('SERVICE_UPDATED'::character varying)::text,
        ('SERVICE_DELETED'::character varying)::text,
        ('TARIFF_CREATED'::character varying)::text,
        ('TARIFF_UPDATED'::character varying)::text,
        ('TARIFF_DELETED'::character varying)::text,
        ('TESTIMONIAL_FEATURED'::character varying)::text,
        ('TESTIMONIAL_UNFEATURED'::character varying)::text,
        ('TESTIMONIAL_REORDERED'::character varying)::text,
        ('TESTIMONIAL_EDITED'::character varying)::text,
        ('TESTIMONIAL_DELETED'::character varying)::text,
        ('SITE_CONTENT_UPDATED'::character varying)::text,
        ('FEEDBACK_STATUS_CHANGED'::character varying)::text,
        ('FEEDBACK_NOTE_UPDATED'::character varying)::text,
        ('NEWS_CREATED'::character varying)::text,
        ('NEWS_UPDATED'::character varying)::text,
        ('NEWS_DELETED'::character varying)::text,
        ('LEGAL_DOCUMENT_UPDATED'::character varying)::text,
        ('STORY_CREATED'::character varying)::text,
        ('STORY_UPDATED'::character varying)::text,
        ('STORY_DELETED'::character varying)::text,
        ('USER_DELETED_IDENTIFIERS_REVEALED'::character varying)::text,
        ('USER_REPORT_ASSIGNED'::character varying)::text,
        ('USER_REPORT_STATUS_CHANGED'::character varying)::text,
        ('USER_RESTRICTION_SCHEDULED'::character varying)::text,
        ('USER_RESTRICTION_ACTIVATED'::character varying)::text,
        ('USER_RESTRICTION_EXPIRED'::character varying)::text,
        ('WEBHOOK_REQUEUED'::character varying)::text,
        ('MEMBER_EXPIRED'::character varying)::text]))
);
