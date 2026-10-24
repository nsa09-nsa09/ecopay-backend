-- B7: provider fee attributed to a payout (ABSORB = EcoPay expense, DEDUCT = subtracted from the
-- owner's amount) and the reason a due payout is deferred (e.g. below the minimum payout amount).
ALTER TABLE payouts
    ADD COLUMN provider_fee_kzt NUMERIC(12, 2),
    ADD COLUMN deferred_reason  VARCHAR(40);
