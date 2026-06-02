-- =========================================================
-- V10 — Room access type + structured provider restrictions
-- Adds a room-level access type and a small set of structured
-- provider restriction flags. Defaults are inherited from the
-- linked tariff_plans.operator_rules at room-creation time
-- (hybrid model — see RoomService.createRoom).
-- =========================================================

ALTER TABLE rooms
    ADD COLUMN IF NOT EXISTS access_type              VARCHAR(20),
    ADD COLUMN IF NOT EXISTS region_restriction       VARCHAR(10),
    ADD COLUMN IF NOT EXISTS requires_email_for_invite BOOLEAN,
    ADD COLUMN IF NOT EXISTS email_change_forbidden   BOOLEAN,
    ADD COLUMN IF NOT EXISTS access_grant_sla_hours   INTEGER;

ALTER TABLE rooms
    DROP CONSTRAINT IF EXISTS chk_rooms_access_type;
ALTER TABLE rooms
    ADD CONSTRAINT chk_rooms_access_type
    CHECK (access_type IS NULL
           OR access_type IN ('FAMILY_PLAN', 'SHARED_ACCOUNT', 'INVITE_LINK', 'EMAIL_INVITE'));

-- ----- Backfill existing rooms from their tariff's operator_rules (fallbacks otherwise) -----
UPDATE rooms r
SET access_type = COALESCE(
        (SELECT tp.operator_rules ->> 'defaultAccessType'
         FROM tariff_plans tp WHERE tp.id = r.tariff_plan_id),
        'FAMILY_PLAN')
WHERE r.access_type IS NULL;

UPDATE rooms r
SET region_restriction = (SELECT tp.operator_rules ->> 'region'
                          FROM tariff_plans tp WHERE tp.id = r.tariff_plan_id)
WHERE r.region_restriction IS NULL AND r.tariff_plan_id IS NOT NULL;

UPDATE rooms r
SET requires_email_for_invite = (SELECT (tp.operator_rules ->> 'requiresEmailForInvite')::boolean
                                 FROM tariff_plans tp WHERE tp.id = r.tariff_plan_id)
WHERE r.requires_email_for_invite IS NULL AND r.tariff_plan_id IS NOT NULL;

UPDATE rooms r
SET email_change_forbidden = (SELECT (tp.operator_rules ->> 'emailChangeForbidden')::boolean
                              FROM tariff_plans tp WHERE tp.id = r.tariff_plan_id)
WHERE r.email_change_forbidden IS NULL AND r.tariff_plan_id IS NOT NULL;

UPDATE rooms r
SET access_grant_sla_hours = COALESCE(
        (SELECT (tp.operator_rules ->> 'accessGrantSlaHours')::int
         FROM tariff_plans tp WHERE tp.id = r.tariff_plan_id),
        24)
WHERE r.access_grant_sla_hours IS NULL;
