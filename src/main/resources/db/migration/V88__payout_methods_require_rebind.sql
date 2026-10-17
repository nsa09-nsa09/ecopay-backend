-- Existing payout methods were created from purchase card storage (cardstorage/add2) or from
-- purchase saved cards. Their payout compatibility cannot be proven, so they are NOT migrated
-- silently: they stop being dispatchable (payouts wait in PENDING_METHOD, money stays held) until
-- the owner reconnects a card through the payout-card flow.
UPDATE payout_methods
   SET status = 'REQUIRES_REBIND', is_default = FALSE
 WHERE status = 'ACTIVE'
   AND (token_source IS NULL OR token_source <> 'PAYOUT_CARD_TOKEN');
