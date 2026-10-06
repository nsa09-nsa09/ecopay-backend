-- Records where a payout destination token came from. Only tokens produced by FreedomPay's
-- payout-card tokenization (cardstoragepayout/add) are proven usable with /api/reg2reg.
ALTER TABLE payout_methods ADD COLUMN IF NOT EXISTS token_source VARCHAR(40);
