-- =========================================================
-- V35 — Service logo column.
--
-- Replaces the placeholder "first letter of name" badge on the catalog cards
-- with an actual logo. Stored exactly like avatars/news images: only the S3
-- object key lives on the row (e.g. service-logos/<uuid>.jpg), the bytes
-- themselves live in the bucket and are streamed back through the backend.
-- =========================================================

ALTER TABLE services ADD COLUMN IF NOT EXISTS logo_key VARCHAR(255);
