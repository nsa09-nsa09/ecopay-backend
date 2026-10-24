-- B5: refunds may be settled by an operator by hand when automatic dispatch is off.
ALTER TABLE refund_transactions
    DROP CONSTRAINT IF EXISTS chk_refund_transactions_status;

ALTER TABLE refund_transactions
    ADD CONSTRAINT chk_refund_transactions_status
    CHECK (status IN (
        'PENDING','PENDING_PROVIDER','SUCCESS','FAILED','REQUIRES_REVIEW',
        'PENDING_MANUAL','SUCCESS_MANUAL'
    ));

-- B1: ON_HOLD was a payout status in code but never allowed by the CHECK; the balance gate parks
-- payouts there, so add it (along with the long-present CLAWBACK_REQUIRED legacy value).
ALTER TABLE payouts
    DROP CONSTRAINT IF EXISTS chk_payouts_status;

ALTER TABLE payouts
    ADD CONSTRAINT chk_payouts_status
    CHECK (status IN (
        'PENDING','PENDING_METHOD','FROZEN','PROCESSING','PENDING_PROVIDER',
        'SUCCESS','FAILED','REQUIRES_REVIEW','REVERSED','CANCELED',
        'ON_HOLD','BLOCKED','CLAWBACK_REQUIRED'
    ));
