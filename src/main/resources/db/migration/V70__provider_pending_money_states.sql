ALTER TABLE refund_transactions
    DROP CONSTRAINT IF EXISTS chk_refund_transactions_status;

ALTER TABLE refund_transactions
    ADD CONSTRAINT chk_refund_transactions_status
    CHECK (status IN ('PENDING','PENDING_PROVIDER','SUCCESS','FAILED','REQUIRES_REVIEW'));

-- Payout status was historically free-form. Constrain all states used by the hardened state
-- machine, including frozen and provider-accepted-but-not-final operations.
ALTER TABLE payouts
    DROP CONSTRAINT IF EXISTS chk_payouts_status;

ALTER TABLE payouts
    ADD CONSTRAINT chk_payouts_status
    CHECK (status IN (
        'PENDING','PENDING_METHOD','FROZEN','PROCESSING','PENDING_PROVIDER',
        'SUCCESS','FAILED','REQUIRES_REVIEW','REVERSED','CANCELED'
    ));
