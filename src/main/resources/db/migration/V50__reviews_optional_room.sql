-- =========================================================
-- V50 — Reviews are now open beyond the "shared room" context.
--
-- room_id becomes optional so any authenticated user can rate any other
-- user, with or without a specific room to anchor the review. Uniqueness
-- is enforced in the service layer via an upsert on (author, recipient);
-- the partial index below defends against duplicate roomless rows for
-- clients that skip the service path (e.g. direct SQL migrations).
-- =========================================================

ALTER TABLE reviews ALTER COLUMN room_id DROP NOT NULL;

-- Backstop uniqueness for profile-level reviews (room_id IS NULL). The
-- existing uq_reviews_author_recipient_room covers roomed reviews.
CREATE UNIQUE INDEX IF NOT EXISTS uq_reviews_author_recipient_noroom
    ON reviews (author_id, recipient_id) WHERE room_id IS NULL;
