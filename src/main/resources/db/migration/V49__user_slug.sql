-- =========================================================
-- V47 — Add users.slug (human-readable public handle).
--
-- The slug is optional at the DB level so seed rows can still be inserted
-- without providing one. New accounts get a slug assigned in the service
-- layer (SlugService.assignInitialSlug). public_id stays as the stable
-- immutable identifier and profile-resolution fallback.
-- =========================================================

-- 1) Nullable column so seeds and pre-existing rows don't break.
ALTER TABLE users ADD COLUMN IF NOT EXISTS slug VARCHAR(30);

-- 2) Backfill legacy rows: reuse public_id as the initial slug so any
--    existing links keep resolving. Set-based UPDATE (CockroachDB-safe).
UPDATE users SET slug = public_id WHERE slug IS NULL;

-- 3) UNIQUE over a nullable column — Postgres/Cockroach allow multiple NULLs.
ALTER TABLE users ADD CONSTRAINT uk_users_slug UNIQUE (slug);
