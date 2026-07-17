-- =========================================================
-- V50 — Trust rating moves to a 10-point scale:
--   * Peer review ratings are now 1..10 (old 1..5 values are doubled,
--     so 4/5 becomes 8/10 — same relative position on the scale).
--   * users.reputation keeps its 0..100 storage (score = rating × 10,
--     rendered as X.X/10) but changes meaning: it is now the average
--     peer rating, not a penalty countdown from 100.
--   * A user with no reviews sits at the neutral default 50 (= 5.0/10),
--     not at 100 — new users are "unknown", not "perfect".
--   * Existing rows are recomputed here with the same formula
--     ReputationService now uses (avg × 10, minus 20 per confirmed
--     owner violation, clamped to 0..100).
-- =========================================================

-- 1) Reviews: rescale 1..5 → 2..10 and widen the range check.
ALTER TABLE reviews DROP CONSTRAINT IF EXISTS reviews_rating_check;

UPDATE reviews SET rating = rating * 2;

ALTER TABLE reviews
    ADD CONSTRAINT reviews_rating_check CHECK (rating BETWEEN 1 AND 10);

-- 2) Users: neutral default for fresh accounts is 50 (= 5.0/10).
ALTER TABLE users ALTER COLUMN reputation SET DEFAULT 50;

-- 3) Recompute stored reputations under the new model.
UPDATE users u
SET reputation = GREATEST(0, LEAST(100,
        COALESCE(
            (SELECT ROUND(AVG(r.rating) * 10)::int
               FROM reviews r
              WHERE r.recipient_id = u.id
                AND r.hidden_by_admin = FALSE),
            50)
        - 20 * COALESCE(
            (SELECT COUNT(*)::int
               FROM disputes d
               JOIN rooms rm ON rm.id = d.room_id
              WHERE rm.owner_user_id = u.id
                AND d.status = 'RESOLVED'
                AND d.decision = 'OWNER_VIOLATION_CONFIRMED'),
            0)))
WHERE u.deleted_at IS NULL;

-- 4) Guard the storage range at the DB level.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_reputation_check;
ALTER TABLE users
    ADD CONSTRAINT users_reputation_check CHECK (reputation BETWEEN 0 AND 100);
