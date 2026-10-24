-- Supports the owner held-balance lookup:
-- user + currency + pre-dispatch status + release window.
CREATE INDEX IF NOT EXISTS idx_payouts_user_currency_status_release
    ON payouts (user_id, currency, status, release_at);
