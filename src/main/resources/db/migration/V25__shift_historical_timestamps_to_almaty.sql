-- =========================================================
-- V25 — One-time historical-data shift from UTC wall clock to Asia/Almaty wall clock.
--
-- Background:
--   Until this migration the JVM ran in UTC, so every entity using
--   LocalDateTime.now() persisted a UTC wall clock into PostgreSQL TIMESTAMP
--   (no zone) columns. JSON responses then returned that UTC wall clock as a
--   zoneless ISO string, which the frontend displayed five hours behind real
--   Almaty time.
--
--   From this release onwards:
--     * JVM default timezone is pinned to Asia/Almaty (SplitUpAuthApplication).
--     * hibernate.jdbc.time_zone = Asia/Almaty.
--     * Jackson 2.x and 3.x mappers serialize LocalDateTime as "+05:00" ISO.
--
--   With that combination LocalDateTime.now() now yields Almaty wall clock for
--   any new row. To stop existing rows from appearing five hours in the past,
--   we shift them once by +5 hours so they too represent Almaty wall clock.
--   Doing this here (and NOT compensating at read time) keeps the codebase
--   consistent: one rule, one place, no double-shift hazard.
--
--   Excluded columns:
--     * rooms.start_date — user-supplied at room creation, so the wall clock
--       already reflects what the owner picked in Almaty. Shifting it would
--       silently push room start times five hours into the future.
--     * Any external-provider field (e.g. freedom_webhook_inbox.received_at)
--       could likewise contain provider-supplied timestamps, but in practice
--       the column is set to LocalDateTime.now() on receipt, so it is shifted.
--
--   Audit logs (admin_action_log, room_event_log, payment_event_log) are
--   protected by BEFORE UPDATE triggers from V4/V14. We disable those triggers
--   for the duration of this migration so the one-off shift can proceed, then
--   re-enable them so append-only enforcement resumes.
-- =========================================================

-- Allow the one-off shift on append-only audit log tables.
ALTER TABLE admin_action_log DISABLE TRIGGER trg_block_aal_update;
ALTER TABLE room_event_log   DISABLE TRIGGER trg_block_rel_update;
ALTER TABLE payment_event_log DISABLE TRIGGER trg_block_pel_update;

-- ----- users -----
UPDATE users
SET created_at        = created_at        + INTERVAL '5 hours' WHERE created_at IS NOT NULL;
UPDATE users SET updated_at         = updated_at         + INTERVAL '5 hours' WHERE updated_at IS NOT NULL;
UPDATE users SET last_login_at      = last_login_at      + INTERVAL '5 hours' WHERE last_login_at IS NOT NULL;
UPDATE users SET phone_verified_at  = phone_verified_at  + INTERVAL '5 hours' WHERE phone_verified_at IS NOT NULL;
UPDATE users SET banned_at          = banned_at          + INTERVAL '5 hours' WHERE banned_at IS NOT NULL;
UPDATE users SET deleted_at         = deleted_at         + INTERVAL '5 hours' WHERE deleted_at IS NOT NULL;

-- ----- rooms (start_date is user input → not shifted) -----
UPDATE rooms SET created_at                = created_at                + INTERVAL '5 hours' WHERE created_at IS NOT NULL;
UPDATE rooms SET updated_at                = updated_at                + INTERVAL '5 hours' WHERE updated_at IS NOT NULL;
UPDATE rooms SET ready_for_verification_at = ready_for_verification_at + INTERVAL '5 hours' WHERE ready_for_verification_at IS NOT NULL;
UPDATE rooms SET completed_at              = completed_at              + INTERVAL '5 hours' WHERE completed_at IS NOT NULL;
UPDATE rooms SET blocked_at                = blocked_at                + INTERVAL '5 hours' WHERE blocked_at IS NOT NULL;
UPDATE rooms SET deleted_at                = deleted_at                + INTERVAL '5 hours' WHERE deleted_at IS NOT NULL;

-- ----- room_members -----
UPDATE room_members SET created_at                = created_at                + INTERVAL '5 hours' WHERE created_at IS NOT NULL;
UPDATE room_members SET updated_at                = updated_at                + INTERVAL '5 hours' WHERE updated_at IS NOT NULL;
UPDATE room_members SET deleted_at                = deleted_at                + INTERVAL '5 hours' WHERE deleted_at IS NOT NULL;
UPDATE room_members SET consent_accepted_at       = consent_accepted_at       + INTERVAL '5 hours' WHERE consent_accepted_at IS NOT NULL;
UPDATE room_members SET owner_access_confirmed_at = owner_access_confirmed_at + INTERVAL '5 hours' WHERE owner_access_confirmed_at IS NOT NULL;
UPDATE room_members SET member_confirmed_at       = member_confirmed_at       + INTERVAL '5 hours' WHERE member_confirmed_at IS NOT NULL;
UPDATE room_members SET activated_at              = activated_at              + INTERVAL '5 hours' WHERE activated_at IS NOT NULL;
UPDATE room_members SET rejected_at               = rejected_at               + INTERVAL '5 hours' WHERE rejected_at IS NOT NULL;
UPDATE room_members SET ended_at                  = ended_at                  + INTERVAL '5 hours' WHERE ended_at IS NOT NULL;

-- ----- room_member_identifiers -----
UPDATE room_member_identifiers SET created_at = created_at + INTERVAL '5 hours' WHERE created_at IS NOT NULL;
UPDATE room_member_identifiers SET updated_at = updated_at + INTERVAL '5 hours' WHERE updated_at IS NOT NULL;

-- ----- login_attempts -----
UPDATE login_attempts SET attempt_time = attempt_time + INTERVAL '5 hours' WHERE attempt_time IS NOT NULL;

-- ----- audit logs -----
UPDATE admin_action_log SET created_at = created_at + INTERVAL '5 hours' WHERE created_at IS NOT NULL;
UPDATE room_event_log   SET created_at = created_at + INTERVAL '5 hours' WHERE created_at IS NOT NULL;
UPDATE payment_event_log SET created_at = created_at + INTERVAL '5 hours' WHERE created_at IS NOT NULL;

-- ----- payments -----
UPDATE payment_intents SET created_at      = created_at      + INTERVAL '5 hours' WHERE created_at IS NOT NULL;
UPDATE payment_intents SET updated_at      = updated_at      + INTERVAL '5 hours' WHERE updated_at IS NOT NULL;
UPDATE payment_intents SET expires_at      = expires_at      + INTERVAL '5 hours' WHERE expires_at IS NOT NULL;
UPDATE payment_intents SET last_webhook_at = last_webhook_at + INTERVAL '5 hours' WHERE last_webhook_at IS NOT NULL;

UPDATE payment_transactions SET created_at = created_at + INTERVAL '5 hours' WHERE created_at IS NOT NULL;
UPDATE payment_transactions SET updated_at = updated_at + INTERVAL '5 hours' WHERE updated_at IS NOT NULL;

UPDATE refund_transactions SET created_at = created_at + INTERVAL '5 hours' WHERE created_at IS NOT NULL;
UPDATE refund_transactions SET updated_at = updated_at + INTERVAL '5 hours' WHERE updated_at IS NOT NULL;

-- ----- support -----
UPDATE support_tickets SET created_at = created_at + INTERVAL '5 hours' WHERE created_at IS NOT NULL;
UPDATE support_tickets SET updated_at = updated_at + INTERVAL '5 hours' WHERE updated_at IS NOT NULL;
UPDATE support_tickets SET closed_at  = closed_at  + INTERVAL '5 hours' WHERE closed_at IS NOT NULL;

UPDATE support_messages SET created_at = created_at + INTERVAL '5 hours' WHERE created_at IS NOT NULL;

-- ----- disputes / moderation -----
UPDATE disputes SET created_at  = created_at  + INTERVAL '5 hours' WHERE created_at IS NOT NULL;
UPDATE disputes SET updated_at  = updated_at  + INTERVAL '5 hours' WHERE updated_at IS NOT NULL;
UPDATE disputes SET resolved_at = resolved_at + INTERVAL '5 hours' WHERE resolved_at IS NOT NULL;

UPDATE moderation_queue SET created_at = created_at + INTERVAL '5 hours' WHERE created_at IS NOT NULL;
UPDATE moderation_queue SET updated_at = updated_at + INTERVAL '5 hours' WHERE updated_at IS NOT NULL;

-- ----- catalog -----
UPDATE categories SET created_at = created_at + INTERVAL '5 hours' WHERE created_at IS NOT NULL;
UPDATE categories SET updated_at = updated_at + INTERVAL '5 hours' WHERE updated_at IS NOT NULL;

UPDATE services SET created_at = created_at + INTERVAL '5 hours' WHERE created_at IS NOT NULL;
UPDATE services SET updated_at = updated_at + INTERVAL '5 hours' WHERE updated_at IS NOT NULL;

UPDATE tariff_plans SET created_at = created_at + INTERVAL '5 hours' WHERE created_at IS NOT NULL;
UPDATE tariff_plans SET updated_at = updated_at + INTERVAL '5 hours' WHERE updated_at IS NOT NULL;

-- ----- auth tokens -----
UPDATE refresh_tokens SET created_at = created_at + INTERVAL '5 hours' WHERE created_at IS NOT NULL;
UPDATE refresh_tokens SET expires_at = expires_at + INTERVAL '5 hours' WHERE expires_at IS NOT NULL;

UPDATE password_reset_tokens SET created_at = created_at + INTERVAL '5 hours' WHERE created_at IS NOT NULL;
UPDATE password_reset_tokens SET expires_at = expires_at + INTERVAL '5 hours' WHERE expires_at IS NOT NULL;

UPDATE email_verification_tokens SET created_at = created_at + INTERVAL '5 hours' WHERE created_at IS NOT NULL;
UPDATE email_verification_tokens SET expires_at = expires_at + INTERVAL '5 hours' WHERE expires_at IS NOT NULL;

UPDATE phone_verifications SET created_at  = created_at  + INTERVAL '5 hours' WHERE created_at IS NOT NULL;
UPDATE phone_verifications SET expires_at  = expires_at  + INTERVAL '5 hours' WHERE expires_at IS NOT NULL;
UPDATE phone_verifications SET verified_at = verified_at + INTERVAL '5 hours' WHERE verified_at IS NOT NULL;

UPDATE staff_two_factor_challenges SET created_at    = created_at    + INTERVAL '5 hours' WHERE created_at IS NOT NULL;
UPDATE staff_two_factor_challenges SET expires_at    = expires_at    + INTERVAL '5 hours' WHERE expires_at IS NOT NULL;
UPDATE staff_two_factor_challenges SET used_at       = used_at       + INTERVAL '5 hours' WHERE used_at IS NOT NULL;
UPDATE staff_two_factor_challenges SET last_sent_at  = last_sent_at  + INTERVAL '5 hours' WHERE last_sent_at IS NOT NULL;

-- ----- cards / payouts -----
UPDATE saved_cards SET created_at = created_at + INTERVAL '5 hours' WHERE created_at IS NOT NULL;
UPDATE saved_cards SET revoked_at = revoked_at + INTERVAL '5 hours' WHERE revoked_at IS NOT NULL;

UPDATE payout_methods SET created_at  = created_at  + INTERVAL '5 hours' WHERE created_at IS NOT NULL;
UPDATE payout_methods SET verified_at = verified_at + INTERVAL '5 hours' WHERE verified_at IS NOT NULL;
UPDATE payout_methods SET revoked_at  = revoked_at  + INTERVAL '5 hours' WHERE revoked_at IS NOT NULL;

UPDATE payouts SET created_at   = created_at   + INTERVAL '5 hours' WHERE created_at IS NOT NULL;
UPDATE payouts SET updated_at   = updated_at   + INTERVAL '5 hours' WHERE updated_at IS NOT NULL;
UPDATE payouts SET processed_at = processed_at + INTERVAL '5 hours' WHERE processed_at IS NOT NULL;

-- ----- misc -----
UPDATE reviews SET created_at = created_at + INTERVAL '5 hours' WHERE created_at IS NOT NULL;

UPDATE service_reviews SET created_at = created_at + INTERVAL '5 hours' WHERE created_at IS NOT NULL;
UPDATE service_reviews SET updated_at = updated_at + INTERVAL '5 hours' WHERE updated_at IS NOT NULL;

UPDATE site_content SET updated_at = updated_at + INTERVAL '5 hours' WHERE updated_at IS NOT NULL;

UPDATE freedom_webhook_inbox SET received_at  = received_at  + INTERVAL '5 hours' WHERE received_at IS NOT NULL;
UPDATE freedom_webhook_inbox SET processed_at = processed_at + INTERVAL '5 hours' WHERE processed_at IS NOT NULL;

-- Restore append-only enforcement.
ALTER TABLE admin_action_log ENABLE TRIGGER trg_block_aal_update;
ALTER TABLE room_event_log   ENABLE TRIGGER trg_block_rel_update;
ALTER TABLE payment_event_log ENABLE TRIGGER trg_block_pel_update;
