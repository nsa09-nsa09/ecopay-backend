-- Login throttling now has a second, source-address bucket in addition to the per-account one.
-- Failures are counted with indexed COUNT queries instead of loading every attempt row.
ALTER TABLE login_attempts ADD COLUMN IF NOT EXISTS ip_address VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_login_attempts_ip_time
    ON login_attempts(ip_address, attempt_time);
