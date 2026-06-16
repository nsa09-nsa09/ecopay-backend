-- =========================================================
-- V31 — Indexes backing GET /api/v1/admin/search
--
-- The endpoint does case-insensitive substring matches across a handful of
-- columns. Btree on LOWER(column) won't help a fully-wildcarded "%foo%"
-- pattern, but it does help:
--   • exact-paste lookups (the operator pastes a publicId / phone),
--   • prefix lookups (the typical "type ahead" path: "foo%"),
--   • ORDER BY created_at DESC after the filter.
--
-- pg_trgm GIN indexes would beat btree for the leading-wildcard case, but
-- enabling the extension needs superuser on Neon, so we keep the indexes
-- conservative. If/when search latency becomes a real concern we can add
-- pg_trgm GIN indexes in a later migration.
-- =========================================================

-- users.public_id is unique-short (V17). Operators paste it whole, so
-- equality / prefix matches dominate.
CREATE INDEX IF NOT EXISTS idx_users_public_id_lower
    ON users (LOWER(public_id));

-- users.phone is already unique (entity-level); a LOWER expression index
-- isn't necessary because phone is already digits. Add a plain btree to
-- help the prefix-match path.
CREATE INDEX IF NOT EXISTS idx_users_phone
    ON users (phone);

-- rooms.title is the only free-text field the catalog list filters on.
CREATE INDEX IF NOT EXISTS idx_rooms_title_lower
    ON rooms (LOWER(title));

-- feedback.subject is short and indexed for the admin triage path.
-- (message is TEXT and intentionally not indexed — it can be MB-sized.)
CREATE INDEX IF NOT EXISTS idx_feedback_subject_lower
    ON feedback (LOWER(subject));
