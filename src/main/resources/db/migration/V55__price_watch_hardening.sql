ALTER TABLE price_watch_provider
    ALTER COLUMN url DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS lease_owner VARCHAR(100),
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_price_watch_provider_lease
    ON price_watch_provider (lease_until, lease_owner);

ALTER TABLE price_snapshot
    ALTER COLUMN outcome TYPE VARCHAR(40),
    ADD COLUMN IF NOT EXISTS body_hash VARCHAR(64);

ALTER TABLE price_snapshot
    DROP CONSTRAINT IF EXISTS chk_price_snapshot_outcome;

ALTER TABLE price_snapshot
    ADD CONSTRAINT chk_price_snapshot_outcome CHECK (
        outcome IN (
            'SUCCESS',
            'NOT_MODIFIED',
            'PARSE_FAILED',
            'FETCH_FAILED',
            'DNS_BLOCKED',
            'URL_BLOCKED',
            'REDIRECT_BLOCKED',
            'RESPONSE_TOO_LARGE',
            'UNSUPPORTED_CONTENT_TYPE',
            'DECOMPRESSION_FAILED',
            'CURRENCY_MISMATCH',
            'REQUIRES_JS',
            'RATE_LIMITED',
            'BLOCKED'
        )
    );
