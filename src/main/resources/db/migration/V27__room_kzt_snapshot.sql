-- =========================================================
-- V27 — Room price snapshot in KZT.
--
-- A room is priced in its owner's chosen currency (rooms.currency). To make
-- cross-currency aggregates (admin revenue dashboard, search filters, "value
-- saved" calculations) trivial, we freeze the conversion at creation time:
--
--   price_total_kzt      = price_total      * fx_rate_to_kzt
--   price_per_member_kzt = price_per_member * fx_rate_to_kzt
--
-- The rate snapshot does NOT change after the room is created — the room's
-- owner committed to a KZT-equivalent price at creation; later FX moves are
-- carried as P&L by EcoPay, not pushed onto members.
--
-- For historical rooms we assume KZT (the original default), so fx_rate_to_kzt
-- is set to 1 and *_kzt mirrors the source price. This keeps existing data
-- usable by the new analytics queries without manual migration.
-- =========================================================

ALTER TABLE rooms ADD COLUMN IF NOT EXISTS fx_rate_to_kzt       NUMERIC(18, 6);
ALTER TABLE rooms ADD COLUMN IF NOT EXISTS price_total_kzt      NUMERIC(14, 2);
ALTER TABLE rooms ADD COLUMN IF NOT EXISTS price_per_member_kzt NUMERIC(14, 2);

-- Backfill: every pre-existing row was effectively KZT (the historical default).
UPDATE rooms
   SET fx_rate_to_kzt       = 1,
       price_total_kzt      = price_total,
       price_per_member_kzt = price_per_member
 WHERE fx_rate_to_kzt IS NULL;
