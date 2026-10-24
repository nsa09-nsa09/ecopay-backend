# FreedomPay Pay-In — Go-Live Checklist

## Environment Variables (required in production)

| Variable | Purpose |
|----------|---------|
| `ECOPAY_PAYMENTS_PROVIDER` | Must be `freedompay` |
| `ECOPAY_PAYMENTS_FREEDOMPAY_BASE_URL` | Production API: `https://api.freedompay.kz` |
| `ECOPAY_PAYMENTS_FREEDOMPAY_MERCHANT_ID` | Merchant ID from FreedomPay dashboard |
| `ECOPAY_PAYMENTS_FREEDOMPAY_SECRET_KEY` | Merchant secret for pay-in signatures |
| `ECOPAY_PAYMENTS_FREEDOMPAY_PAYOUT_SECRET_KEY` | Separate secret for payout signatures |
| `ECOPAY_PAYMENTS_FREEDOMPAY_TEST_MODE` | `0` (live charges) |
| `ECOPAY_PAYMENTS_FREEDOMPAY_RESULT_URL` | `https://<backend>/api/v1/webhooks/freedompay/result` |
| `ECOPAY_PAYMENTS_FREEDOMPAY_CHECK_URL` | `https://<backend>/api/v1/webhooks/freedompay/check` |
| `ECOPAY_PAYMENTS_FREEDOMPAY_SUCCESS_URL` | `https://<frontend>/payment/confirmation` |
| `ECOPAY_PAYMENTS_FREEDOMPAY_FAILURE_URL` | `https://<frontend>/payment/failure` |

## FreedomPay Merchant Dashboard

1. Set **result URL** to the `RESULT_URL` above (POST callback).
2. Enable **pg_can_reject=1** so the merchant can reject before capture.
3. Set **pg_check_url** to the `CHECK_URL` above (pre-debit verification).
4. Verify **pg_auto_clearing=1** (one-step capture) is accepted.

## ProductionStartupGuard

`ProductionStartupGuard` (active under `prod` profile) blocks startup if:
- Provider is not `freedompay` or is in test mode
- Any callback URL is not HTTPS or points to a sandbox/test host
- Merchant ID or secret keys are blank
- Signature verification is disabled
- Check URL is missing or misconfigured

## Webhook Endpoints

| Path | Method | Purpose |
|------|--------|---------|
| `/api/v1/webhooks/freedompay/result` | POST | Post-payment result (pg_can_reject flow) |
| `/api/v1/webhooks/freedompay/check` | POST | Pre-debit verification (slot availability) |

## Reconciliation

- Scheduled reconciler runs every 5 minutes for open intents.
- After 10 failed attempts, intent escalates to `REQUIRES_REVIEW` with admin notification.
- Max 50 total attempts before the reconciler stops querying the provider.

## Money Rules

- All amounts: `BigDecimal`, scale 2, `HALF_UP`.
- Every state change writes to `payment_event_log` and `money_ledger`.
- Owner payouts are held for 30 days (`app.payout.hold-days=30`).

## Saved Cards

- `pg_recurring_start=1` tokenizes a card on the first charge.
- Renewal payments accept `savedCardId` to charge via `make_recurring_payment`.
- Recurring auto-charge is **disabled** for MVP (`app.recurring.enabled=false`).
