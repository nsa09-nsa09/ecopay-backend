-- =========================================================
-- V26 — Deduplicated visitor tracking.
--
-- One row per (visitor_id, visit_date) so the unique-visitor count cannot be
-- inflated by client-side reloads. The cookie-issued visitor_id (UUID) is
-- written from POST /api/v1/analytics/visit; repeat hits on the same calendar
-- day only bump page_count and refresh last_seen_at.
--
-- visit_date is stored as a calendar DATE (no time-of-day) so the unique
-- constraint can be enforced by the DB and aggregations stay O(rows-per-day).
-- The calendar boundary is computed in Asia/Almaty by the service layer (now
-- that JVM tz is pinned to Almaty in V25 onwards).
--
-- user_id is filled lazily: an anonymous session becomes attributable as soon
-- as the same browser hits the endpoint while authenticated.
-- =========================================================

CREATE TABLE IF NOT EXISTS site_visit (
    id                  BIGSERIAL    PRIMARY KEY,
    visitor_id          UUID         NOT NULL,
    visit_date          DATE         NOT NULL,
    first_seen_at       TIMESTAMP    NOT NULL,
    last_seen_at        TIMESTAMP    NOT NULL,
    page_count          INTEGER      NOT NULL DEFAULT 1,
    is_authenticated    BOOLEAN      NOT NULL DEFAULT FALSE,
    user_id             BIGINT       REFERENCES users(id) ON DELETE SET NULL,
    last_path           VARCHAR(255),

    CONSTRAINT uq_site_visit_visitor_date UNIQUE (visitor_id, visit_date)
);

CREATE INDEX IF NOT EXISTS idx_site_visit_visit_date
    ON site_visit (visit_date);

CREATE INDEX IF NOT EXISTS idx_site_visit_visitor_id
    ON site_visit (visitor_id);

CREATE INDEX IF NOT EXISTS idx_site_visit_user_id
    ON site_visit (user_id)
    WHERE user_id IS NOT NULL;
