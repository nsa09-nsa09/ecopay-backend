-- Bookkeeping for provider status reconciliation of ambiguous payment intents and refunds.
ALTER TABLE payment_intents ADD COLUMN IF NOT EXISTS last_reconciled_at TIMESTAMP;
ALTER TABLE payment_intents ADD COLUMN IF NOT EXISTS reconcile_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE refund_transactions ADD COLUMN IF NOT EXISTS provider_submitted_at TIMESTAMP;
ALTER TABLE refund_transactions ADD COLUMN IF NOT EXISTS last_reconciled_at TIMESTAMP;
ALTER TABLE payouts ADD COLUMN IF NOT EXISTS submitted_at TIMESTAMP;
