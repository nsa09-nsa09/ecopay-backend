-- B1: time series of the merchant payout balance at FreedomPay, written by the periodic
-- balance_status check and by pg_balance carried in each reg2reg payout response.
CREATE TABLE payout_balance_snapshots (
    id          BIGSERIAL PRIMARY KEY,
    balance     NUMERIC(14, 2),
    currency    VARCHAR(10)  NOT NULL DEFAULT 'KZT',
    source      VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_payout_balance_snapshots_created_at
    ON payout_balance_snapshots (created_at DESC);
