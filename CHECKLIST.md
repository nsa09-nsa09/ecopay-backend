# EcoPay Backend — DoD Checklist

Tracks status of platform-wide requirements from `CLAUDE.md`. `[x]` = shipped, `[~]` = partial, `[ ]` = not yet.

## Auth & accounts
- [x] Email + password registration, BCrypt hashing
- [x] JWT access (15 min) + refresh (hashed, rotated, single-use)
- [x] Refresh-token reuse → session revoke
- [x] Email verification required to login (anti-enumeration on resend)
- [x] Password reset via email (anti-enumeration on request)
- [x] SMS phone verification (opt-in)
- [x] Staff (ADMIN/SUPPORT) email-OTP 2FA on login
- [x] Login rate limiting + temporary lockout
- [ ] Google Sign-In

## Rooms & members
- [x] Room lifecycle OPEN→IN_VERIFICATION→ACTIVE→COMPLETED + CANCELLED/BLOCKED
- [x] Verification modes AUTO / RISK_BASED / ADMIN_REQUIRED
- [x] Atomic join with optimistic locking; slot capacity respected
- [x] Owner cannot remove/reject member after charge SUCCESS
- [x] TELECOM identifier encrypted at-rest, revealed to owner only post-payment, every reveal logged
- [x] Auto-verify on (paid + access granted + identifier valid + no flags + no open dispute)
- [x] Pending-membership SLA scheduler → opens ACCESS_ISSUE ticket
- [x] Connection identifier reveal endpoint scoped to room owner / staff (logged)

## Payments / Payouts / Refunds  (gateway-owned — read-only on this branch)
- [x] Provider abstracted behind `PaymentGatewayRegistry` (FreedomPay + mock)
- [x] Idempotency keys on intents/refunds/payouts
- [x] Refund: user-initiated (own charge only) + admin-driven, partial/full
- [x] Payout: platform fee deducted, dispatched to user-owned card token (anti-IDOR on method registration)
- [x] Clawback: not-yet-paid payout reversed on full refund; dispatched/partial → CLAWBACK_REQUIRED event
- [x] Recurring charges via `RecurringChargeService`
- [x] Webhook idempotency for provider callbacks
- [x] Refund/payout: IDOR check on user-scoped read endpoints (`/me`, `/{id}`)

## Reviews & reputation
- [x] Only members of the same room may review each other
- [x] Self-review blocked
- [x] One review per (author, recipient, room)
- [x] Eligibility restricted to COMPLETED rooms (period actually ended)
- [x] Admin-hidden reviews excluded from public list, `averageRating`, `reviewsCount`
- [x] Public read endpoints (permitAll) at `/api/v1/reputation/**`

## Support / Disputes / Moderation
- [x] User-scoped ticket reads (IDOR-safe queries)
- [x] Staff queue, assign, status, escalate
- [x] Disputes: user read-only own; admin queue + decision + sanctions
- [x] Moderation queue: confirm/reject membership, block room, ban user
- [x] Batch-confirm only when no red flags; every individual action logged
- [~] File attachments: controller surface present, storage pending

## Logs & audit
- [x] `admin_action_log` (append-only, DB role enforced)
- [x] `room_event_log` (append-only, correlation_id, idempotency_key)
- [x] Payment event logger (`PaymentEventLogger`)
- [x] Identifier reveal logged (actor, member, reason)
- [x] Refund/payout state changes logged

## Security cross-cutting
- [x] Stateless JWT + custom `JwtAuthenticationFilter`
- [x] CSP, HSTS, Frame-DENY, Referrer-Policy, X-Content-Type-Options headers
- [x] CORS via `CorsProperties`
- [x] Role checks: `/admin/**` → ADMIN, `/staff/**` → ADMIN+SUPPORT
- [x] Optimistic locking on rooms & memberships
- [x] User text sanitization (reviews/tickets) via `TextSanitizer`
- [x] Field-level encryption for TELECOM identifiers (`AesFieldEncryptionService`)
- [x] Spring Validation on every DTO
- [ ] OWASP Top 10 full audit pass — pending
- [ ] STRIDE threat modeling document — pending
- [ ] IDOR unit/integration test suite — partial coverage in `service/` tests

## Background & operations
- [x] `CleanupScheduler` (token/cache hygiene)
- [x] `RoomVerificationScheduler` (auto-verify gate)
- [x] `PendingMembershipEscalationScheduler` (SLA → access-issue ticket)
- [x] Flyway migrations + healthcheck endpoint
- [ ] Backup/restore drill — pending
- [ ] WebSocket notifications + outbox dispatcher — not started

## API
- [x] Versioning under `/api/v1/`
- [x] Swagger / OpenAPI (`OpenApiConfig`)
- [x] Unified error format via `exception/` handler
- [x] Pagination on heavy list endpoints
- [~] Async dispatch for emails/SMS — direct send today; outbox not yet
