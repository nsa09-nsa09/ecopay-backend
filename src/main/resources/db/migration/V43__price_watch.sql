-- =========================================================
-- V43 — Price Watch module.
--
-- Live subscription-price monitor for third-party platforms (Netflix, Spotify,
-- streaming, cloud, etc.). The admin registers "providers" — a URL + extraction
-- recipe — and a background scheduler polls each one on its own cadence,
-- storing every observation as a snapshot and recording a "price_change" row
-- whenever the observed price differs from the last stored one.
--
-- Tables:
--   price_watch_provider — one row per platform/plan we track (Netflix Basic,
--                          Spotify Duo, ...). Holds the extraction recipe, the
--                          last observed price and the health status.
--   price_snapshot       — every fetch attempt, success or failure. Kept for
--                          history and for troubleshooting broken extractors.
--                          Composite index on (provider_id, captured_at DESC)
--                          serves the admin history view.
--   price_change         — narrow log of "old vs new price" events. Drives the
--                          admin notification lane (unacknowledged=true feed).
--
-- The schema is additive; no existing tables are touched. Seed data leaves the
-- Netflix ru-TJ example in MANUAL mode (headless browsing is a v2 item) plus
-- two AUTO placeholders the admin will fine-tune from the UI.
-- =========================================================

CREATE TABLE IF NOT EXISTS price_watch_provider (
    id                       BIGSERIAL     PRIMARY KEY,
    platform_code            VARCHAR(64)   NOT NULL,
    display_name             VARCHAR(200)  NOT NULL,
    plan_name                VARCHAR(200)  NOT NULL,
    url                      TEXT          NOT NULL,
    locale                   VARCHAR(20),
    expected_currency        VARCHAR(10),
    extractor_type           VARCHAR(20)   NOT NULL DEFAULT 'AUTO',
    extractor_config         JSONB         NOT NULL DEFAULT '{}'::jsonb,
    requires_js              BOOLEAN       NOT NULL DEFAULT FALSE,
    check_interval_minutes   INTEGER       NOT NULL DEFAULT 720,
    active                   BOOLEAN       NOT NULL DEFAULT TRUE,
    status                   VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    consecutive_failures     INTEGER       NOT NULL DEFAULT 0,
    last_checked_at          TIMESTAMPTZ,
    last_success_at          TIMESTAMPTZ,
    next_check_at            TIMESTAMPTZ,
    last_price               NUMERIC(12,2),
    last_currency            VARCHAR(10),
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_price_watch_provider_extractor CHECK (
        extractor_type IN ('AUTO', 'JSON_LD', 'META', 'CSS', 'REGEX', 'MANUAL')
    ),
    CONSTRAINT chk_price_watch_provider_status CHECK (
        status IN ('OK', 'STALE', 'FAILING', 'BLOCKED', 'PENDING')
    )
);

CREATE INDEX IF NOT EXISTS idx_price_watch_provider_active_next_check
    ON price_watch_provider (active, next_check_at);

CREATE INDEX IF NOT EXISTS idx_price_watch_provider_platform
    ON price_watch_provider (platform_code);

CREATE TABLE IF NOT EXISTS price_snapshot (
    id             BIGSERIAL     PRIMARY KEY,
    provider_id    BIGINT        NOT NULL REFERENCES price_watch_provider(id) ON DELETE CASCADE,
    price          NUMERIC(12,2),
    currency       VARCHAR(10),
    captured_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    outcome        VARCHAR(20)   NOT NULL,
    http_status    INTEGER,
    raw_excerpt    TEXT,
    error_message  TEXT,

    CONSTRAINT chk_price_snapshot_outcome CHECK (
        outcome IN ('SUCCESS', 'PARSE_FAILED', 'FETCH_FAILED', 'BLOCKED')
    )
);

CREATE INDEX IF NOT EXISTS idx_price_snapshot_provider_captured
    ON price_snapshot (provider_id, captured_at DESC);

CREATE TABLE IF NOT EXISTS price_change (
    id             BIGSERIAL     PRIMARY KEY,
    provider_id    BIGINT        NOT NULL REFERENCES price_watch_provider(id) ON DELETE CASCADE,
    old_price      NUMERIC(12,2),
    new_price      NUMERIC(12,2) NOT NULL,
    currency       VARCHAR(10),
    changed_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    snapshot_id    BIGINT        REFERENCES price_snapshot(id) ON DELETE SET NULL,
    acknowledged   BOOLEAN       NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_price_change_ack_changed
    ON price_change (acknowledged, changed_at DESC);

-- ---------------------------------------------------------
-- Seed a handful of examples so the admin UI is never empty on first boot.
-- Netflix stays MANUAL — its checkout page is JS-heavy and geo/token-gated;
-- headless browsing is planned for v2. The remaining two are AUTO so the
-- extractor pipeline (JSON-LD → meta → CSS → sweep) can take a first shot.
-- ---------------------------------------------------------
INSERT INTO price_watch_provider (
    platform_code, display_name, plan_name, url, locale, expected_currency,
    extractor_type, extractor_config, requires_js, active, status
) VALUES
    ('netflix', 'Netflix', 'Basic (Tajikistan)',
     'https://www.netflix.com/tj-ru/', 'ru-TJ', 'USD',
     'MANUAL', '{}'::jsonb, TRUE, TRUE, 'PENDING'),
    ('spotify', 'Spotify', 'Individual',
     'https://www.spotify.com/kz-ru/premium/', 'ru-KZ', 'KZT',
     'AUTO', '{}'::jsonb, FALSE, TRUE, 'PENDING'),
    ('youtube-premium', 'YouTube Premium', 'Individual',
     'https://www.youtube.com/premium', 'en', 'USD',
     'AUTO', '{}'::jsonb, FALSE, TRUE, 'PENDING')
ON CONFLICT DO NOTHING;
