-- Block 5d: model the acquiring cost (the provider's fee on each charge), which was modelled
-- NOWHERE — so EcoPay "revenue" was gross commission and overstated margin. Nullable and additive:
-- no existing value or field meaning changes, and rows stay valid when the provider does not report
-- a fee. The intent carries the fee from the charge response to the committed transaction, which is
-- what the admin dashboard aggregates for net revenue.

ALTER TABLE payment_intents ADD COLUMN IF NOT EXISTS provider_fee_amount NUMERIC(12, 2);
ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS provider_fee_amount NUMERIC(12, 2);
