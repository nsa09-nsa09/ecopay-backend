-- =========================================================
-- V18 — Service-reviews carousel.
-- New entity, separate from peer reviews (table `reviews`, V6).
-- One review per author (author_id UNIQUE). featured is set by admin
-- only; the (featured, created_at DESC) index serves the homepage
-- carousel query.
-- =========================================================

CREATE TABLE IF NOT EXISTS service_reviews (
    id          BIGSERIAL PRIMARY KEY,
    author_id   BIGINT      NOT NULL UNIQUE REFERENCES users(id),
    rating      INTEGER     NOT NULL,
    text        TEXT        NOT NULL,
    featured    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP,

    CONSTRAINT chk_service_reviews_rating CHECK (rating BETWEEN 1 AND 5)
);

CREATE INDEX IF NOT EXISTS idx_service_reviews_featured_created
    ON service_reviews (featured, created_at DESC);
