ALTER TABLE refund_transactions
    ADD COLUMN IF NOT EXISTS refund_request_id BIGINT REFERENCES refund_requests(id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_refund_transactions_refund_request
    ON refund_transactions(refund_request_id)
    WHERE refund_request_id IS NOT NULL;
