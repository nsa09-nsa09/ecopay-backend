# Ecopay API E2E tests

HTTP-level end-to-end tests for the core business chain, run with Playwright's
`request` API (no browser needed) against a **running** backend.

> Location: this suite lives at `backend/api-tests/`. Paths below are relative to it.

## What they cover
- **01-auth** — registration validation, duplicate email (409), login, refresh rotation, `/users/me` auth.
- **02-chain** — the full happy path: register → create room → join → pay (mock gateway) → owner grants → member confirms → `ACTIVE`; payment idempotency; can't double-join; owner can't join own room.
- **03-security** — IDOR: member list needs auth, bystander can't pay for another's membership, non-owner can't grant access, payment amount is server-side, no anonymous payment.

## Prerequisites
The backend must be running in **dev** profile (mock payment gateway) with the
seeded catalog (migration `V7`). From the backend root (`..`):
```powershell
docker compose up -d            # postgres + backend (dev → ecopay.payments.provider=mock)
curl http://localhost:8080/actuator/health   # {"status":"UP"}
```

## Run
```powershell
cd backend/api-tests
npm install            # first time (no browsers downloaded — API only)
npm test               # runs all specs against http://localhost:8080/api/v1/
```
Target a different backend with `API_BASE`:
```powershell
$env:API_BASE = "https://api.ecopay.kz/api/v1/"; npm test    # note the trailing slash
```

> The tests create fresh users on every run (unique email/phone), so they are
> safe to re-run against a persistent database.

## Browser UI smoke tests (Playwright)
Browser-level smoke tests live in `ui-tests/` (`playwright.ui.config.ts`). They need
the **frontend dev server running on :5173** (the only origin the backend CORS allows
in dev), pointed at the local backend via `frontend/.env.local`.

```powershell
# 1) free :5173 if a stale build container holds it
docker stop ecopay-frontend
# 2) start the dev server (binds :5173) — from the frontend repo
cd ../../frontend; pnpm dev
# 3) install the browser once, then run (from backend/api-tests)
cd ../backend/api-tests
npx playwright install chromium
npm run test:ui
```
Covers: login screen renders, registration via the UI → routes to phone verification,
landing reachable without auth. Override the target with `UI_BASE`.
