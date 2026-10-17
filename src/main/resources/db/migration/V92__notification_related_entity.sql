-- B9 admin operational alerts: let a notification point at the domain entity it is about (payment
-- intent / payout / refund id) so repeated alerts for the same entity+type can be de-duplicated.
-- Additive and idempotent; PostgreSQL 16 (CI Testcontainers) and CockroachDB (dev/prod).

ALTER TABLE notifications ADD COLUMN IF NOT EXISTS related_entity_id BIGINT NULL;

-- Supports the "already alerted for this entity+type?" existence check.
CREATE INDEX IF NOT EXISTS idx_notifications_type_related_entity
    ON notifications (type, related_entity_id);
