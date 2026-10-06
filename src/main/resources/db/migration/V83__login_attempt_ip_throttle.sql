-- Block 4c: per-source-IP login throttle to catch credential stuffing that cycles through many
-- emails from a single host (the per-email bucket alone never sees such an attack).
--
-- Adds a nullable ip column plus a (ip, attempt_time) index so the failed-attempt COUNT query is
-- index-backed, mirroring the existing idx_email_attempt on (email, attempt_time) that now backs
-- the per-email COUNT (replacing the old load-all-rows-and-stream approach). Nullable + IF NOT
-- EXISTS so the change is safe on existing rows (historical attempts simply have a null ip).

ALTER TABLE login_attempts ADD COLUMN IF NOT EXISTS ip VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_login_attempt_ip ON login_attempts (ip, attempt_time);
