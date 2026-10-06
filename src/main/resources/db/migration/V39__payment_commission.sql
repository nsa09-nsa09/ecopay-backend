-- Phase 39: Tiered EcoPay commission, charged on top of each member's tariff share.
-- The room owner pays no commission — only joining members do. The member is charged
-- (share + commission); the owner is paid the full share; EcoPay keeps the commission.
-- This replaces the old flat percentage platform fee deducted from the owner payout.
--
-- Stored per intent so the owner payout can be derived exactly as (amount - commission),
-- independent of any later change to the commission tiers.

ALTER TABLE payment_intents
    ADD COLUMN IF NOT EXISTS commission_amount NUMERIC(12,2) NOT NULL DEFAULT 0;
