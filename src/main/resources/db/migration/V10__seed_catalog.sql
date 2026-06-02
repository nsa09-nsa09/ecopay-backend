-- =========================================================
-- V7 — Seed catalog (categories / services / tariff_plans)
-- Idempotent: safe to run on an already-seeded DB.
-- Needed so that room creation + payment flows have real data
-- to reference (catalog was empty → create-room impossible).
-- =========================================================

-- ----- Categories -----
INSERT INTO categories (name, slug, sort_order)
VALUES
    ('Видеостриминг', 'video-streaming', 10),
    ('Музыка',        'music',           20),
    ('Подписки',      'digital-bundles', 30),
    ('Операторы связи','telecom',        40)
ON CONFLICT (slug) DO NOTHING;

-- ----- Services -----
INSERT INTO services (category_id, name, slug, provider_type)
SELECT c.id, v.name, v.slug, v.provider_type
FROM (VALUES
    ('Netflix',      'netflix',      'DIGITAL', 'video-streaming'),
    ('Yandex Plus',  'yandex-plus',  'DIGITAL', 'video-streaming'),
    ('Spotify',      'spotify',      'DIGITAL', 'music'),
    ('Apple Music',  'apple-music',  'DIGITAL', 'music'),
    ('YouTube Premium','youtube-premium','DIGITAL','digital-bundles'),
    ('Beeline',      'beeline',      'OPERATOR', 'telecom'),
    ('Tele2',        'tele2',        'OPERATOR', 'telecom'),
    ('Kcell',        'kcell',        'OPERATOR', 'telecom')
) AS v(name, slug, provider_type, category_slug)
JOIN categories c ON c.slug = v.category_slug
ON CONFLICT (slug) DO NOTHING;

-- ----- Tariff plans -----
INSERT INTO tariff_plans (service_id, name, period_type, max_members, base_price_total, currency, connection_type)
SELECT s.id, t.name, t.period_type, t.max_members, t.base_price_total, 'KZT', t.connection_type
FROM (VALUES
    ('netflix',         'Netflix Premium (4K)',   'MONTHLY', 4, 7290.00, NULL),
    ('yandex-plus',     'Яндекс Плюс Семья',      'MONTHLY', 4, 1990.00, NULL),
    ('spotify',         'Spotify Family',         'MONTHLY', 6, 2700.00, NULL),
    ('apple-music',     'Apple Music Family',     'MONTHLY', 6, 2990.00, NULL),
    ('youtube-premium', 'YouTube Premium Family', 'MONTHLY', 5, 3690.00, NULL),
    ('beeline',         'Beeline Семья 5',        'MONTHLY', 5, 6000.00, 'SIM'),
    ('tele2',           'Tele2 Вместе',           'MONTHLY', 4, 4800.00, 'SIM'),
    ('kcell',           'Kcell Family',           'MONTHLY', 4, 5200.00, 'SIM')
) AS t(service_slug, name, period_type, max_members, base_price_total, connection_type)
JOIN services s ON s.slug = t.service_slug
WHERE NOT EXISTS (
    SELECT 1 FROM tariff_plans tp
    WHERE tp.service_id = s.id AND tp.name = t.name
);
