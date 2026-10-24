-- =========================================================
-- V54 — services.access_type
--
-- Which contact a joining member has to hand over so the owner can add
-- them: EMAIL (Spotify, YouTube, Apple — invite goes to the member's own
-- address), PHONE (telecom operators, Ivi — the account IS the number) or
-- BOTH (Yandex ID accepts either).
--
-- Distinct from rooms.access_type (V12), which says HOW the owner grants
-- access (family plan / shared account / invite link). This one says WHAT
-- the member must provide.
--
-- Idempotent: safe to re-run.
-- =========================================================

ALTER TABLE services
    ADD COLUMN IF NOT EXISTS access_type VARCHAR(10);

-- ----- Per-service values for the seeded catalog -----
UPDATE services SET access_type = v.access_type
FROM (VALUES
    -- Invite is sent to the member's own account address.
    ('netflix',          'EMAIL'),
    ('spotify',          'EMAIL'),
    ('apple-music',      'EMAIL'),
    ('youtube-premium',  'EMAIL'),
    ('chatgpt',          'EMAIL'),
    ('apple-one',        'EMAIL'),
    ('microsoft-365',    'EMAIL'),
    ('playstation-plus', 'EMAIL'),
    ('steam',            'EMAIL'),
    ('xbox-game-pass',   'EMAIL'),
    -- Account is the phone number.
    ('ivi',              'PHONE'),
    ('beeline',          'PHONE'),
    ('tele2',            'PHONE'),
    ('kcell',            'PHONE'),
    -- Yandex ID resolves either an address or a number.
    ('yandex-plus',      'BOTH')
) AS v(slug, access_type)
WHERE services.slug = v.slug;

-- ----- Fallback for anything else (admin-created services, future seeds) -----
UPDATE services
SET access_type = CASE
        WHEN provider_type IN ('OPERATOR', 'ISP') THEN 'PHONE'
        ELSE 'EMAIL'
    END
WHERE access_type IS NULL;

ALTER TABLE services
    ALTER COLUMN access_type SET DEFAULT 'EMAIL';

ALTER TABLE services
    ALTER COLUMN access_type SET NOT NULL;

ALTER TABLE services
    DROP CONSTRAINT IF EXISTS chk_services_access_type;

ALTER TABLE services
    ADD CONSTRAINT chk_services_access_type
    CHECK (access_type IN ('EMAIL', 'PHONE', 'BOTH'));

-- ----- Members can now hand over an email, not just a telecom identifier -----
ALTER TABLE room_member_identifiers
    DROP CONSTRAINT IF EXISTS chk_room_member_identifiers_type;

ALTER TABLE room_member_identifiers
    ADD CONSTRAINT chk_room_member_identifiers_type
    CHECK (identifier_type IN ('PHONE', 'ACCOUNT', 'SIM', 'ESIM', 'EMAIL'));
