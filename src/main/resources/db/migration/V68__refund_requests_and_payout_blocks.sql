CREATE TABLE IF NOT EXISTS refund_requests (
    id                      BIGSERIAL PRIMARY KEY,
    payment_transaction_id  BIGINT NOT NULL REFERENCES payment_transactions(id),
    requester_user_id       BIGINT NOT NULL REFERENCES users(id),
    reason_code             VARCHAR(40) NOT NULL,
    description             VARCHAR(1000) NOT NULL,
    status                  VARCHAR(30) NOT NULL DEFAULT 'REQUESTED',
    approved_amount         NUMERIC(12,2),
    decided_by_user_id      BIGINT REFERENCES users(id),
    decision_note           VARCHAR(1000),
    idempotency_key         VARCHAR(100) NOT NULL,
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP,
    decided_at              TIMESTAMP,
    CONSTRAINT uq_refund_requests_idempotency UNIQUE (idempotency_key),
    CONSTRAINT chk_refund_requests_status
        CHECK (status IN ('REQUESTED','UNDER_REVIEW','APPROVED','REJECTED')),
    CONSTRAINT chk_refund_requests_reason
        CHECK (reason_code IN (
            'ACCESS_NOT_PROVIDED','ACCESS_NOT_WORKING','ACCESS_REVOKED','MATERIAL_MISMATCH',
            'DUPLICATE_CHARGE','WRONG_AMOUNT','UNAUTHORIZED_PAYMENT','OTHER'
        )),
    CONSTRAINT chk_refund_requests_approved_amount
        CHECK (approved_amount IS NULL OR approved_amount > 0)
);

CREATE INDEX IF NOT EXISTS idx_refund_requests_status_created
    ON refund_requests(status, created_at);

CREATE TABLE IF NOT EXISTS payout_blocks (
    id          BIGSERIAL PRIMARY KEY,
    payout_id   BIGINT NOT NULL REFERENCES payouts(id),
    source_type VARCHAR(30) NOT NULL,
    source_id   BIGINT NOT NULL,
    reason_code VARCHAR(80) NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    released_at TIMESTAMP,
    CONSTRAINT uq_payout_blocks_source UNIQUE (payout_id, source_type, source_id),
    CONSTRAINT chk_payout_blocks_source
        CHECK (source_type IN ('SUPPORT_TICKET','DISPUTE','REFUND_REQUEST','RISK','ADMIN')),
    CONSTRAINT chk_payout_blocks_status CHECK (status IN ('ACTIVE','RELEASED'))
);

CREATE INDEX IF NOT EXISTS idx_payout_blocks_active ON payout_blocks(payout_id, status);

-- Existing held payouts must not be released automatically on the first deployment of this
-- safety migration. Operations can be reviewed and unblocked explicitly after reconciliation.
INSERT INTO payout_blocks (payout_id, source_type, source_id, reason_code, status)
SELECT p.id, 'ADMIN', p.id, 'V68_EXISTING_PAYOUT_REVIEW', 'ACTIVE'
FROM payouts p
WHERE p.status IN ('PENDING', 'PENDING_METHOD')
ON CONFLICT (payout_id, source_type, source_id) DO NOTHING;

UPDATE payouts
SET status = 'FROZEN', failure_reason = 'Blocked: V68_EXISTING_PAYOUT_REVIEW'
WHERE status IN ('PENDING', 'PENDING_METHOD');
