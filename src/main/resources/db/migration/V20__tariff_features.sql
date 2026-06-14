-- =========================================================
-- V20 — Tariff "плюшки": features column on tariff_plans. Stored as
-- a JSONB array of strings so each tariff can advertise an arbitrary list
-- of perks ("4K", "без рекламы", "семейный аккаунт", ...) without a side
-- table. Defaulted to empty array so existing rows validate.
-- =========================================================

ALTER TABLE tariff_plans
    ADD COLUMN IF NOT EXISTS features JSONB NOT NULL DEFAULT '[]'::jsonb;
