-- =========================================================
-- V15 — Allow the OWNER_VERIFICATION_CHANGED admin action type.
-- Admins can now grant/revoke a user's "verified owner" flag, which is
-- audited like every other admin action (see AdminActionLog).
-- =========================================================

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
        'REFUND_INITIATED',
        'REFUND_APPROVED',
        'REFUND_REJECTED',
        'DISPUTE_RESOLVED',
        'BATCH_CONFIRM',
        'OWNER_VERIFICATION_CHANGED'
    ));
