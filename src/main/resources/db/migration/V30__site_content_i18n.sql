-- =========================================================
-- V30 — Tri-lingual About Us (kk / ru / en) on site_content.
--
-- We add a parallel set of columns per language for the three editorial fields
-- (title, mission, description). The legacy single-locale columns stay in
-- place so any older client / cached page continues to work; they're now
-- effectively "the Russian copy mirrored at write time" until we cut over the
-- callers in a follow-up.
--
-- Existing copy is backfilled into the *_ru columns since RU is the
-- product's default locale (CLAUDE.md i18n section).
-- =========================================================

ALTER TABLE site_content ADD COLUMN IF NOT EXISTS title_kz       TEXT;
ALTER TABLE site_content ADD COLUMN IF NOT EXISTS title_ru       TEXT;
ALTER TABLE site_content ADD COLUMN IF NOT EXISTS title_en       TEXT;

ALTER TABLE site_content ADD COLUMN IF NOT EXISTS mission_kz     TEXT;
ALTER TABLE site_content ADD COLUMN IF NOT EXISTS mission_ru     TEXT;
ALTER TABLE site_content ADD COLUMN IF NOT EXISTS mission_en     TEXT;

ALTER TABLE site_content ADD COLUMN IF NOT EXISTS description_kz TEXT;
ALTER TABLE site_content ADD COLUMN IF NOT EXISTS description_ru TEXT;
ALTER TABLE site_content ADD COLUMN IF NOT EXISTS description_en TEXT;

-- Seed: legacy single-locale columns map to *_ru since the original copy
-- (V24 seed) was authored in Russian.
UPDATE site_content
   SET title_ru       = COALESCE(title_ru, title),
       mission_ru     = COALESCE(mission_ru, mission),
       description_ru = COALESCE(description_ru, description);
