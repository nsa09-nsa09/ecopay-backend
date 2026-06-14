-- =========================================================
-- V23 — Avatars moved to S3-compatible object storage (Cloudflare R2).
-- users.avatar now holds only the object KEY (e.g. "avatars/<uuid>.jpg"),
-- never an absolute URL. The viewable link is minted on read as a short-lived
-- pre-signed GET, so the same value resolves on localhost and in production.
--
-- Legacy rows held either the old local-disk path ("/api/v1/users/avatars/...")
-- or a user-pasted public URL ("http..."). Those are no longer resolvable, so
-- we clear anything that isn't a managed object key to avoid broken images.
-- =========================================================

UPDATE users
SET avatar = NULL
WHERE avatar IS NOT NULL
  AND avatar NOT LIKE 'avatars/%';
