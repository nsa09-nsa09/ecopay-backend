-- =========================================================
-- V8 — Enforce append-only on the remaining audit log tables
-- at the DB level (admin_action_log, room_event_log), mirroring
-- the protection already applied to payment_event_log in V4.
-- Satisfies CLAUDE.md: "Logs (Append-Only) — Enforced at DB role level."
-- =========================================================

CREATE OR REPLACE FUNCTION block_audit_log_modify()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit log table % is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

-- ----- admin_action_log -----
DROP TRIGGER IF EXISTS trg_block_aal_update ON admin_action_log;
DROP TRIGGER IF EXISTS trg_block_aal_delete ON admin_action_log;

CREATE TRIGGER trg_block_aal_update
    BEFORE UPDATE ON admin_action_log
    FOR EACH ROW EXECUTE FUNCTION block_audit_log_modify();

CREATE TRIGGER trg_block_aal_delete
    BEFORE DELETE ON admin_action_log
    FOR EACH ROW EXECUTE FUNCTION block_audit_log_modify();

-- ----- room_event_log -----
DROP TRIGGER IF EXISTS trg_block_rel_update ON room_event_log;
DROP TRIGGER IF EXISTS trg_block_rel_delete ON room_event_log;

CREATE TRIGGER trg_block_rel_update
    BEFORE UPDATE ON room_event_log
    FOR EACH ROW EXECUTE FUNCTION block_audit_log_modify();

CREATE TRIGGER trg_block_rel_delete
    BEFORE DELETE ON room_event_log
    FOR EACH ROW EXECUTE FUNCTION block_audit_log_modify();
