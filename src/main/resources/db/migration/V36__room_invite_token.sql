-- =========================================================
-- V36 — Per-room invite token.
--
-- Backs the "copy invite link" feature on the room owner's screen. We removed
-- the /browse catalog page, so the link is the intentional way to send a
-- specific room URL to a person outside the platform.
--
-- The column is nullable: existing rooms have no token until the owner first
-- hits GET /rooms/{id}/invite-link, which lazy-mints one. The partial unique
-- index keeps two rooms from colliding on a token without forcing every
-- legacy row to backfill a placeholder.
-- =========================================================

ALTER TABLE rooms ADD COLUMN IF NOT EXISTS invite_token VARCHAR(40);

CREATE UNIQUE INDEX IF NOT EXISTS uq_rooms_invite_token
    ON rooms (invite_token)
    WHERE invite_token IS NOT NULL;
