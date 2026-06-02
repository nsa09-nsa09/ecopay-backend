-- =========================================================
-- V9 — Catalog expansion
-- Adds categories (AI / games / cloud / education) and popular
-- services (ChatGPT, Apple One, Microsoft 365, PlayStation Plus,
-- Steam, Xbox Game Pass, Ivi) on top of the V7 seed.
--
-- Also seeds tariff_plans.operator_rules (JSONB) with a structured
-- shape consumed later as defaults for room access type / provider
-- restrictions (see F2). Shape:
--   {
--     "defaultAccessType": "FAMILY_PLAN" | "SHARED_ACCOUNT" | "INVITE_LINK" | "EMAIL_INVITE",
--     "region": "KZ" | null,
--     "requiresEmailForInvite": bool,
--     "emailChangeForbidden": bool,
--     "accessGrantSlaHours": int,
--     "sharingWarning": string | null
--   }
--
-- Idempotent: safe to run on an already-seeded DB.
-- =========================================================

-- ----- New categories -----
INSERT INTO categories (name, slug, sort_order)
VALUES
    ('Искусственный интеллект', 'ai',        50),
    ('Игры',                    'games',     60),
    ('Облако и продуктивность', 'cloud',     70),
    ('Образование',             'education', 80)
ON CONFLICT (slug) DO NOTHING;

-- ----- New services (all DIGITAL) -----
INSERT INTO services (category_id, name, slug, provider_type)
SELECT c.id, v.name, v.slug, v.provider_type
FROM (VALUES
    ('ChatGPT',          'chatgpt',          'DIGITAL', 'ai'),
    ('Apple One',        'apple-one',        'DIGITAL', 'digital-bundles'),
    ('Microsoft 365',    'microsoft-365',    'DIGITAL', 'cloud'),
    ('PlayStation Plus', 'playstation-plus', 'DIGITAL', 'games'),
    ('Steam',            'steam',            'DIGITAL', 'games'),
    ('Xbox Game Pass',   'xbox-game-pass',   'DIGITAL', 'games'),
    ('Ivi',              'ivi',              'DIGITAL', 'video-streaming')
) AS v(name, slug, provider_type, category_slug)
JOIN categories c ON c.slug = v.category_slug
ON CONFLICT (slug) DO NOTHING;

-- ----- Tariff plans for the new services (with operator_rules defaults) -----
INSERT INTO tariff_plans (service_id, name, period_type, max_members, base_price_total, currency, connection_type, operator_rules)
SELECT s.id, t.name, t.period_type, t.max_members, t.base_price_total, 'KZT', t.connection_type, t.operator_rules::jsonb
FROM (VALUES
    ('chatgpt',          'ChatGPT Team',          'MONTHLY', 4, 13000.00, NULL,
        '{"defaultAccessType":"SHARED_ACCOUNT","region":null,"requiresEmailForInvite":true,"emailChangeForbidden":true,"accessGrantSlaHours":24,"sharingWarning":null}'),
    ('chatgpt',          'ChatGPT Plus',          'MONTHLY', 2, 11000.00, NULL,
        '{"defaultAccessType":"SHARED_ACCOUNT","region":null,"requiresEmailForInvite":false,"emailChangeForbidden":true,"accessGrantSlaHours":24,"sharingWarning":"ChatGPT Plus — индивидуальная подписка; совместный доступ может нарушать условия OpenAI."}'),
    ('apple-one',        'Apple One Family',      'MONTHLY', 6, 4490.00,  NULL,
        '{"defaultAccessType":"FAMILY_PLAN","region":null,"requiresEmailForInvite":true,"emailChangeForbidden":false,"accessGrantSlaHours":24,"sharingWarning":null}'),
    ('microsoft-365',    'Microsoft 365 Family',  'MONTHLY', 6, 3990.00,  NULL,
        '{"defaultAccessType":"FAMILY_PLAN","region":null,"requiresEmailForInvite":true,"emailChangeForbidden":false,"accessGrantSlaHours":24,"sharingWarning":null}'),
    ('playstation-plus', 'PlayStation Plus Deluxe','MONTHLY', 2, 6500.00, NULL,
        '{"defaultAccessType":"SHARED_ACCOUNT","region":"KZ","requiresEmailForInvite":false,"emailChangeForbidden":true,"accessGrantSlaHours":24,"sharingWarning":"Совместный доступ к PlayStation Plus ограничен правилами Sony и привязкой к консоли."}'),
    ('steam',            'Steam Family',          'MONTHLY', 5, 0.00,     NULL,
        '{"defaultAccessType":"FAMILY_PLAN","region":"KZ","requiresEmailForInvite":false,"emailChangeForbidden":false,"accessGrantSlaHours":24,"sharingWarning":"Steam Family Sharing ограничивает одновременный доступ к библиотеке."}'),
    ('xbox-game-pass',   'Game Pass Ultimate',    'MONTHLY', 4, 7000.00,  NULL,
        '{"defaultAccessType":"SHARED_ACCOUNT","region":null,"requiresEmailForInvite":false,"emailChangeForbidden":true,"accessGrantSlaHours":24,"sharingWarning":"Совместное использование Game Pass может нарушать условия Microsoft."}'),
    ('ivi',              'Ivi Подписка',          'MONTHLY', 5, 1000.00,  NULL,
        '{"defaultAccessType":"FAMILY_PLAN","region":"KZ","requiresEmailForInvite":false,"emailChangeForbidden":false,"accessGrantSlaHours":24,"sharingWarning":null}')
) AS t(service_slug, name, period_type, max_members, base_price_total, connection_type, operator_rules)
JOIN services s ON s.slug = t.service_slug
WHERE NOT EXISTS (
    SELECT 1 FROM tariff_plans tp
    WHERE tp.service_id = s.id AND tp.name = t.name
);

-- ----- Backfill operator_rules defaults for V7 tariffs (only where empty) -----
-- Digital streaming / music / bundles → family plan, 24h grant SLA.
UPDATE tariff_plans tp
SET operator_rules = '{"defaultAccessType":"FAMILY_PLAN","region":null,"requiresEmailForInvite":true,"emailChangeForbidden":false,"accessGrantSlaHours":24,"sharingWarning":null}'::jsonb
FROM services s
WHERE tp.service_id = s.id
  AND tp.operator_rules IS NULL
  AND s.provider_type = 'DIGITAL';

-- Telecom operators → KZ region, SIM-based family plan, 24h grant SLA.
UPDATE tariff_plans tp
SET operator_rules = '{"defaultAccessType":"FAMILY_PLAN","region":"KZ","requiresEmailForInvite":false,"emailChangeForbidden":false,"accessGrantSlaHours":24,"sharingWarning":null}'::jsonb
FROM services s
WHERE tp.service_id = s.id
  AND tp.operator_rules IS NULL
  AND s.provider_type IN ('OPERATOR', 'ISP');
