-- =========================================================
-- V29 — Indexes that back the new /admin/dashboard distribution charts.
--
-- Each chart filters / groups by a different column set; the existing indexes
-- (V1 idx_rooms_status_start_date, idx_room_members_room_status, V8
-- idx_users_status / idx_users_created_at) are tuned for catalog browsing and
-- admin user search rather than for "WHERE deleted_at IS NULL GROUP BY X".
-- These new indexes are narrow and additive: they don't override anything and
-- they don't fight with ddl-auto=validate (no new columns).
-- =========================================================

-- Most chart queries start with "WHERE deleted_at IS NULL [AND status = ...]".
-- A composite on (deleted_at, status) supports both that filter and the room
-- status distribution chart's "GROUP BY status".
CREATE INDEX IF NOT EXISTS idx_rooms_deleted_at_status
    ON rooms (deleted_at, status);

-- Category distribution joins rooms → categories on category_id; without an
-- index on the FK side the LEFT JOIN does a sequential scan.
CREATE INDEX IF NOT EXISTS idx_rooms_category_id
    ON rooms (category_id);

-- Currency distribution groups by `currency` over active, non-deleted rooms.
CREATE INDEX IF NOT EXISTS idx_rooms_currency
    ON rooms (currency);

-- Popular-services + active-member counts repeatedly read room_members rows
-- where (deleted_at IS NULL AND status = 'ACTIVE'). The composite covers both
-- the equality filter and the per-room aggregation.
CREATE INDEX IF NOT EXISTS idx_room_members_room_status_deleted
    ON room_members (room_id, status, deleted_at);

-- Operator distribution iterates over every non-deleted user once.
CREATE INDEX IF NOT EXISTS idx_users_deleted_at
    ON users (deleted_at);
