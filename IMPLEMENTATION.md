# EcoPay Backend — Implementation Status

Spring Boot 4 / Java 21 / PostgreSQL / Flyway / JWT / Jackson 3.
Package root: `kz.hrms.splitupauth`. Public API base: `/api/v1/`.

## Module map

| Module | Status | Endpoints (base path) |
|---|---|---|
| Auth + Email/Phone verify + Staff 2FA | shipped | `/api/v1/auth` |
| User profile | shipped | `/api/v1/users` |
| Catalog (categories / services / tariffs) | shipped, public read | `/api/v1/catalog` |
| Rooms (lifecycle, owner actions) | shipped | `/api/v1/rooms` |
| Room members (join, owner-access, confirm, reveal) | shipped | `/api/v1/rooms/{id}/members` |
| Payments (intents, confirm) | shipped (gateway-owned, не трогаем) | `/api/v1/payments` |
| Saved cards | shipped (gateway-owned) | `/api/v1/payments/saved-cards` |
| Payouts (owner withdrawals + method registry) | shipped | `/api/v1/payouts` |
| Refunds (user-initiated + admin-driven) | shipped | `/api/v1/refunds` |
| Disputes (user view) | shipped | `/api/v1/disputes` |
| Reviews + Reputation (public read) | shipped | `/api/v1/reviews`, `/api/v1/reputation` |
| Support tickets (user) | shipped | `/api/v1/support-tickets` |
| Moderation queue (admin) | shipped | `/api/v1/admin/moderation` |
| Admin: users / rooms / disputes / refunds / logs / dashboard | shipped | `/api/v1/admin/**` |
| Staff: support queue / dispute open / identifier reveal | shipped | `/api/v1/staff/**` |
| Webhooks (FreedomPay result + payout-result) | shipped | `/api/v1/webhooks/freedompay/**` |
| Schedulers (cleanup, room verify, pending-membership escalation) | shipped | — |

## Auth

- `POST /auth/register` — email + password + phone, BCrypt, email-verify required to login (dev override: `app.dev.auto-verify-email`).
- `POST /auth/login` — issues access + refresh tokens; ADMIN/SUPPORT accounts go through email-OTP 2FA (`/login/2fa/verify`, `/login/2fa/resend`).
- `POST /auth/refresh` — single-use refresh-token rotation.
- `POST /auth/logout` — revokes refresh token.
- `POST /auth/reset-password` + `/auth/reset-password/confirm` — silent on unknown email (no enumeration).
- `GET /auth/verify-email`, `POST /auth/resend-verification` — anti-enumeration: silent for unknown/already-verified.
- `POST /auth/phone/request-code`, `POST /auth/phone/verify` — SMS verification.
- JWT: HS-signed, 15 min access, hashed refresh in DB, rotation enforced.
- Rate limiting: `InMemoryRateLimiter` on login + sensitive auth endpoints.

## Rooms & Members

- Room lifecycle: `OPEN → IN_VERIFICATION → ACTIVE → COMPLETED` (+ `CANCELLED`, `BLOCKED`).
- Verification modes: `AUTO | ADMIN_REQUIRED | RISK_BASED`.
- Owner endpoints: create, update (only before start_date), ready-for-verification, cancel, complete.
- Member lifecycle: `APPLIED → PENDING → ACTIVE`; alt: `REJECTED | CANCELLED_BEFORE_PAYMENT | BLOCKED_BY_ADMIN`.
- Join requires available slot, `now < start_date`, optimistic locking.
- TELECOM identifier encrypted at-rest (`AesFieldEncryptionService`); revealed only to room owner after charge SUCCESS via `/members/{memberId}/reveal-identifier` (also exposed to staff for moderation, logged).
- Owner-grants-access → member-confirms-access → auto-verify check or admin escalation.
- `PendingMembershipEscalationScheduler` opens system ACCESS_ISSUE ticket if member doesn't confirm in time.

## Payments / Payouts / Refunds

- Payment gateway: FreedomPay (prod) + mock (dev), behind `PaymentGatewayRegistry`. **Owned by parallel work — read-only here.**
- Idempotency on intents, refunds, payouts.
- `PayoutService` creates owner payout on charge success, deducts platform fee, dispatches against registered card token. Anti-IDOR: payout method may only be registered from a card token owned by the user (saved-card check).
- Clawback: `PayoutService.reverseOwnerPayoutForRefund` reverses not-yet-paid payouts on full refund; partial refund or already-dispatched → `CLAWBACK_REQUIRED` event for manual handling.
- Refunds: user can request on own charge (IDOR check); admin can create + finalize; webhooks finalize async PENDING.
- `RecurringChargeService` re-charges saved-card subscriptions on schedule.

## Reviews / Reputation

- `POST /reviews` — author and recipient must both have been members/owner of the same room; recipient ≠ author; room must be `COMPLETED` (period actually ended); one review per (author, recipient, room).
- Reputation/feed: `findByRecipientAndHiddenByAdminFalse*` — admin-hidden reviews never enter public list or `averageRating`/`reviewsCount` aggregates.
- Public read: `GET /reputation/users/{id}`, `GET /reputation/users/{id}/reviews`.

## Support / Disputes / Moderation

- Tickets: user creates ticket (optionally tied to room/member); IDOR — cannot create for a room you're not part of. User-side read endpoints are user-scoped at the query level (`findByIdAndUser`, `findByUserOrderBy*`).
- Staff queue (`/staff/support-tickets/**`): paginated queue + assigned, escalate to dispute, status updates.
- Disputes: user read-only on own; admin queue + assign + decision + owner-violation sanctions.
- Moderation queue: admin only; confirm/reject membership, block room, ban user, batch confirm (only if no red flags), all logged to `admin_action_log`.

## Cross-cutting

- `SecurityConfig`: stateless, JWT filter, CSRF off (token-bearer), CSP/HSTS/Referrer-Policy/Frame-DENY set. Public matchers: auth, webhooks, swagger, actuator/health, catalog GET, reputation GET, `/rooms` and `/rooms/{id}` GET (deeper paths require auth). `/admin/**` → ADMIN, `/staff/**` → ADMIN+SUPPORT.
- Append-only logs: `admin_action_log`, `room_event_log` — enforced via DB role (see migrations).
- Logging events centralised in `PaymentEventLogger` and `RoomEventLogger`.
- Outbox + async: schedulers (`CleanupScheduler`, `RoomVerificationScheduler`, `PendingMembershipEscalationScheduler`) handle background work; email/SMS dispatch isolated in services.
- Text user input sanitised via `TextSanitizer` on review/ticket bodies.

## Known not-in-scope (this branch)

- Payment gateway internals (FreedomPay + savedCard + PaymentController + PaymentService charge paths + PayoutService payout dispatch) — owned by separate in-flight branch.
- WebSocket notifications channel — not wired yet.
- File attachments on tickets — controller hooks present, storage backend pending.
