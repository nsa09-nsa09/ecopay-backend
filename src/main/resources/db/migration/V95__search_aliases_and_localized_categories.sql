-- B18: service search aliases + localised category names.
--
-- services.search_aliases: comma-separated list of lower-case synonyms / common misspellings used
-- by CatalogService.searchActiveByName. Seeded for a handful of frequently-mistyped names so
-- "ютуб" finds YouTube; admins can edit it via the service CRUD afterwards.
--
-- categories.name_ru/kz/en: per-locale display names so the catalog UI can switch language
-- without the frontend owning a translation table.

ALTER TABLE services ADD COLUMN IF NOT EXISTS search_aliases TEXT;

ALTER TABLE categories ADD COLUMN IF NOT EXISTS name_ru VARCHAR(100);
ALTER TABLE categories ADD COLUMN IF NOT EXISTS name_kz VARCHAR(100);
ALTER TABLE categories ADD COLUMN IF NOT EXISTS name_en VARCHAR(100);

-- Seed aliases for the common offenders. Rows missing (not yet imported) are a no-op.
UPDATE services SET search_aliases = 'ютуб, ютюб, ютьюб, youtube, yt'
    WHERE lower(name) IN ('youtube', 'ютуб') AND search_aliases IS NULL;
UPDATE services SET search_aliases = 'нетфликс, нетфлекс, netflix'
    WHERE lower(name) = 'netflix' AND search_aliases IS NULL;
UPDATE services SET search_aliases = 'спотифай, спотик, spotify'
    WHERE lower(name) = 'spotify' AND search_aliases IS NULL;
UPDATE services SET search_aliases = 'майкрософт, офис, office, ворд, эксель, microsoft 365, microsoft'
    WHERE lower(name) LIKE 'microsoft%' AND search_aliases IS NULL;
UPDATE services SET search_aliases = 'билайн, beeline'
    WHERE lower(name) = 'beeline' AND search_aliases IS NULL;
UPDATE services SET search_aliases = 'актив, activ, kcell'
    WHERE lower(name) IN ('activ', 'actib') AND search_aliases IS NULL;

-- Seed localised category names when the current name matches a known key. Everything else
-- gets {@code name} echoed to all three columns so the backend never serves nulls.
UPDATE categories SET name_ru = COALESCE(name_ru, 'Стриминг'),
                      name_kz = COALESCE(name_kz, 'Стриминг'),
                      name_en = COALESCE(name_en, 'Streaming')
    WHERE lower(slug) = 'streaming';
UPDATE categories SET name_ru = COALESCE(name_ru, 'Музыка'),
                      name_kz = COALESCE(name_kz, 'Музыка'),
                      name_en = COALESCE(name_en, 'Music')
    WHERE lower(slug) = 'music';
UPDATE categories SET name_ru = COALESCE(name_ru, 'Программы'),
                      name_kz = COALESCE(name_kz, 'Бағдарламалар'),
                      name_en = COALESCE(name_en, 'Software')
    WHERE lower(slug) = 'software';
UPDATE categories SET name_ru = COALESCE(name_ru, 'Связь'),
                      name_kz = COALESCE(name_kz, 'Байланыс'),
                      name_en = COALESCE(name_en, 'Telecom')
    WHERE lower(slug) = 'telecom';

UPDATE categories SET name_ru = COALESCE(name_ru, name),
                      name_kz = COALESCE(name_kz, name),
                      name_en = COALESCE(name_en, name);
