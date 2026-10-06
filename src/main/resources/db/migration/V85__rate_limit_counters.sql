-- Shared sliding-window counters for JdbcRateLimiter (app.rate-limit.store=jdbc). One row per
-- hashed bucket key and fixed window start (epoch seconds). Keys are SHA-256 digests, never raw
-- emails/phones/IPs. Rows are disposable: expires_at lets the cleanup job purge stale windows.
CREATE TABLE IF NOT EXISTS rate_limit_counters (
    bucket_key   VARCHAR(64) NOT NULL,
    window_start BIGINT      NOT NULL,
    hits         BIGINT      NOT NULL,
    expires_at   BIGINT      NOT NULL,
    PRIMARY KEY (bucket_key, window_start)
);

CREATE INDEX IF NOT EXISTS idx_rate_limit_counters_expires_at
    ON rate_limit_counters(expires_at);
