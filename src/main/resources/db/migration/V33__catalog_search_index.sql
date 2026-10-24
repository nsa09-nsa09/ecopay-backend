-- =========================================================
-- V33 — Index backing the public catalog search (navbar "Поиск планов…").
--
-- GET /api/v1/catalog/search filters services case-insensitively by name. A
-- btree on LOWER(name) won't help fully-wildcarded "%foo%" patterns, but it
-- does cover the typical type-ahead path (prefix match) and the exact-paste
-- case. pg_trgm GIN would beat us on leading-wildcard searches, but enabling
-- it needs superuser on Neon (see V31's rationale) — we keep the index plain.
-- =========================================================

CREATE INDEX IF NOT EXISTS idx_services_name_lower
    ON services (LOWER(name));
