CREATE TABLE identifier_reveal_audit (
  id BIGSERIAL PRIMARY KEY,
  event_id UUID NOT NULL UNIQUE,
  correlation_id UUID,
  actor_user_id BIGINT NOT NULL REFERENCES users(id),
  actor_role VARCHAR(20) NOT NULL,
  room_id BIGINT NOT NULL REFERENCES rooms(id),
  room_member_id BIGINT NOT NULL REFERENCES room_members(id),
  context_type VARCHAR(30),
  context_id BIGINT,
  reason_code VARCHAR(50) NOT NULL,
  outcome VARCHAR(30) NOT NULL,
  request_id UUID,
  client_ip VARCHAR(64),
  user_agent VARCHAR(255),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_identifier_reveal_audit_actor_created
  ON identifier_reveal_audit(actor_user_id, created_at);

CREATE INDEX idx_identifier_reveal_audit_member_created
  ON identifier_reveal_audit(room_member_id, created_at);

CREATE INDEX idx_identifier_reveal_audit_context
  ON identifier_reveal_audit(context_type, context_id);

CREATE INDEX idx_identifier_reveal_audit_outcome_created
  ON identifier_reveal_audit(outcome, created_at);

CREATE OR REPLACE FUNCTION prevent_identifier_reveal_audit_update()
RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'identifier_reveal_audit is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_identifier_reveal_audit_no_update
BEFORE UPDATE OR DELETE ON identifier_reveal_audit
FOR EACH ROW EXECUTE FUNCTION prevent_identifier_reveal_audit_update();
