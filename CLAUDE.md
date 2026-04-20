## graphify

This project has a graphify knowledge graph at graphify-out/.

Rules:
- Before answering architecture or codebase questions, read graphify-out/GRAPH_REPORT.md for god nodes and community structure
- If graphify-out/wiki/index.md exists, navigate it instead of reading raw files
- After modifying code files in this session, run `python3 -c "from graphify.watch import _rebuild_code; from pathlib import Path; _rebuild_code(Path('.'))"` to keep the graph current

---

# Ecopay — Developer Reference (MVP v1)

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Spring Boot (Java), PostgreSQL, Redis |
| API Style | REST JSON, WebSocket (wss:// only) |
| Web Frontend | React + TypeScript |
| Mobile | React Native (web-payment via link for MVP) |
| DevOps | Docker, CI/CD, dev/stage/prod envs |

---

## Module Structure

```
auth/
rooms/
room-members/
payments/
support/
moderation/
reputation/
catalog/
notifications/
admin/
```

All business rules (statuses, transitions, risk flags, timeouts) live in **service layer only**. Frontend contains no business logic.

---

## Database Entities

```
users
categories
services
rooms
room_members
room_member_identifiers   ← TELECOM phone/ID (encrypted at-rest)
reviews
support_tickets
support_messages
admin_action_log          ← append-only, no UPDATE/DELETE at DB role level
room_event_log            ← append-only, no UPDATE/DELETE at DB role level
disputes
payment_intents
payment_transactions
refund_transactions
moderation_queue
```

**Required indexes:**
```sql
rooms(status, start_date)
room_members(room_id, status)
payments(intent_id, status)
admin_action_log(timestamp)
disputes(status, created_at)
```

All entity deletions → soft delete (`deleted_at`). Audit/payment/admin logs → never physically deleted. On user account deletion → anonymize PII, retain financial events.

---

## Roles

| Role | Scope |
|------|-------|
| USER | Global |
| OWNER | Contextual — per room_id only (checked in service layer) |
| SUPPORT | Global |
| ADMIN | Global |

---

## User Entity (MVP minimum)

```
id, email, password_hash (Argon2id or BCrypt),
display_name, avatar_id (from pool),
status: ACTIVE | BANNED,
reputation_score (calculated),
user_risk_score,
created_at
```

Phone/identifier is **NOT** stored in user profile. Lives only in `room_member` / `room_member_identifiers`.

---

## Room

### Fields
```
id, service_id, owner_id,
title, description,
room_type: DIGITAL | TELECOM,
verification_mode: AUTO | ADMIN_REQUIRED | RISK_BASED,
max_members,
price_total, price_per_member,
period: monthly | yearly | other,
start_date,
status: OPEN | IN_VERIFICATION | ACTIVE | COMPLETED | CANCELLED | BLOCKED,
created_at, updated_at (optimistic locking / version column)
```

### Status Transitions
```
OPEN → IN_VERIFICATION   (at start_date automatically, or owner triggers "ready")
IN_VERIFICATION → ACTIVE (auto-verify conditions met, or admin confirms)
OPEN → CANCELLED         (before start_date)
ANY → BLOCKED            (admin action)
ACTIVE → COMPLETED       (period ends)
```

### Business Rules
- `max_members` must be ≥ 2
- Cannot edit critical fields after `start_date` (except via Admin)
- Join allowed only while `now < start_date` AND `(PENDING + ACTIVE) < max_members`
- Slot occupied at statuses: `PENDING` and `ACTIVE` only
- `APPLIED` / `CANCELLED_BEFORE_PAYMENT` / `REJECTED` do NOT occupy a slot

### Default verification_mode (MVP)
- TELECOM rooms → RISK_BASED
- DIGITAL rooms → RISK_BASED
- ADMIN_REQUIRED only when flagged by system or set manually by Admin

### TELECOM Room Extra Fields (owner sets at creation)
```
operator_id (Beeline, Tele2, Kcell, Activ, Altel, Kazakhtelecom, Izi, ...)
tariff_type
connection_method: SIM | eSIM | personal_account
operator_constraints (text, optional)
operator_terms_confirmed: boolean (required checkbox)
```

---

## Room Member

### Fields
```
id, room_id, user_id,
status: APPLIED | PENDING | ACTIVE | REJECTED | CANCELLED_BEFORE_PAYMENT | BLOCKED_BY_ADMIN,
requires_admin_review: boolean,
connection_identifier (encrypted, TELECOM only — revealed to Owner only after payment SUCCESS),
consent_given_at,
owner_access_granted_at,
owner_access_method (invite_link | email_invite | family_plan_add | ...),
member_confirmed_at,
created_at
```

### Member Status Transitions
```
APPLIED  → PENDING                  (PaymentTransaction = SUCCESS)
PENDING  → ACTIVE                   (auto-verify or admin confirm)
PENDING  → REJECTED                 (admin decision)
APPLIED  → CANCELLED_BEFORE_PAYMENT (member or owner cancels before payment)
PENDING/ACTIVE → BLOCKED_BY_ADMIN   (admin only, via Dispute)
```

### Join Flow
1. User selects room
2. TELECOM: user enters phone/identifier + gives consent
3. PaymentIntent created → user pays
4. On `PaymentTransaction = SUCCESS` → member status set to `PENDING`
5. Owner marks "access granted" (method + timestamp)
6. Member sees "Confirm access received" button (deadline: T_confirm, e.g. 24h)
7. Member confirms → auto-verify runs → `ACTIVE`
8. Member does not confirm in time → SupportTicket/Dispute created (idempotent), `requires_admin_review = true`

### Auto-Verify Conditions (all must be true)
- PaymentTransaction = SUCCESS
- Owner marked "access granted"
- TELECOM: connection_identifier filled and format-valid
- No open Dispute / SupportTicket for this room/member
- Neither member nor owner is BANNED / BLOCKED
- `requires_admin_review = false`

### Identifier Reveal Rule
- `connection_identifier` exposed to Owner **only** via backend endpoint
- Condition: `PaymentTransaction = SUCCESS` + caller role = OWNER of that room
- Every access logged: `actor_id, room_id, member_id, timestamp, reason`
- Admin access: only during moderation/dispute, also logged

### Owner Cannot
- Remove/reject member after `PaymentTransaction = SUCCESS`
- Any post-payment member status changes → Admin only (via Dispute flow)

---

## Auto-Verification & Moderation Queue

### SLA
- AUTO / RISK_BASED (no flags): auto-verify within ≤ 1 minute of conditions met
- ADMIN_REQUIRED: target ≤ 24h; on timeout → reminder sent; second timeout → Dispute created (idempotent)

### Red Flags (trigger `requires_admin_review = true` → ModerationQueue)
- High `user_risk_score` on owner or member
- Owner reputation below threshold OR account age < N days
- Abnormally high price or many slots
- Rate anomaly: too many requests/payments in short window
- Owner has open tickets/disputes in last N days
- TELECOM identifier fails format validation
- Repeated REJECTED history for this owner

### ModerationQueue contains only
- Rooms with `verification_mode = ADMIN_REQUIRED`
- Members with `requires_admin_review = true`
- Disputes/tickets linked to a room

---

## Payments

### Entities
```
PaymentIntent       { id, room_member_id, amount, currency, idempotency_key, status: PENDING|SUCCESS|FAILED }
PaymentTransaction  { id, intent_id, provider_ref, status, created_at }
RefundTransaction   { id, transaction_id, amount, reason, initiated_by, status, created_at }
```

### Rules
- Abstract payment layer — pluggable provider, no business logic rewrite needed
- All operations must carry `idempotency_key`
- Refund is idempotent
- Refund types: automatic (owner violation), manual (admin), partial (time-based formula)
- All payment events: append-only log with `actor_id, room_id, reason, amount, timestamp`

### Status Flow
```
PaymentIntent:      PENDING → SUCCESS | FAILED
PaymentTransaction: SUCCESS → REFUNDED (partial | full)
```

---

## Dispute / Support

### SupportTicket
```
id, user_id, room_id (nullable), room_member_id (nullable),
subject, status: OPEN | IN_PROGRESS | CLOSED,
created_at
```

### Dispute
```
id, ticket_id, room_id, room_member_id,
status: OPEN | RESOLVED | REJECTED,
admin_decision, admin_comment (required),
refund_triggered: boolean,
created_at, resolved_at
```

### Flow
1. User creates ticket → Support Agent processes → escalates to Admin as Dispute if payment/room related
2. Admin resolves Dispute → optionally triggers Refund + sanctions

### TELECOM Dispute Reasons
- Phone not connected
- Tariff differs from advertised
- Member disconnected early

### Refund Calculation
- Proportional to unused period
- Account for operator minimum billing period if applicable

---

## Reputation & Reviews

### Review Fields
```
id, author_id, recipient_id, room_id,
rating (1–5), text,
created_at, hidden_by_admin: boolean
```

### Rules
- Only users who shared a room may review each other
- Only after period ends or participation closes
- Admin can hide a review (logged)

### Reputation Score Inputs
- Average rating from reviews
- Count of successfully completed periods
- Count of confirmed disputes/complaints
- Confirmed fraud/violations

---

## Notifications

### Events to Notify
- Member joined room
- Owner marked "access granted"
- Member confirmed / did not confirm access
- Admin confirmed/rejected access
- Dispute created / closed
- Ban / unban
- Room status changed
- SLA timeout warning

### Channels
- Web: in-app + WebSocket (auth required at handshake, rate-limited)
- Mobile: push notifications
- Email: minimum — password reset; optionally other events

### Implementation
- **Outbox pattern required** — notifications must NOT block user-facing API
- All background jobs (risk scoring, SLA reminders, auto-escalation) run async

---

## Admin Panel

### Sections
- Users (view, ban/unban)
- Rooms (view, block, batch-confirm members without red flags)
- Categories / Services (CRUD, `is_active` toggle)
- ModerationQueue (confirm/reject access)
- Disputes / Refunds (view, resolve, initiate refund)
- SupportTickets (view, manage)
- AdminActionLog (read-only)

### Admin Actions on Room Member
- Confirm access → member → ACTIVE
- Reject access → triggers Dispute/Refund
- Block room / Ban owner
- Comment required on **all** dispute decisions
- Batch confirm: allowed only when no red flags; each individual action must be logged

### Triggers Required for Admin to Act
Dispute / SupportTicket / `requires_admin_review` / SLA timeout / fraud anomaly signal

Admin does **NOT** participate in happy-path activation.

---

## Logs (Append-Only)

**Enforced at DB role level — no UPDATE/DELETE permitted on log tables.**

### AdminActionLog
```
id, actor_id, role, action, entity_type, entity_id,
old_state, new_state, comment, ip, user_agent, timestamp
```

### RoomEventLog
```
event_id (UUID), correlation_id, idempotency_key,
room_id, actor_id, event_type, payload, timestamp
```
Event types: `room_created`, `member_joined`, `payment_intent_created`, `payment_success`, `owner_access_granted`, `member_confirmed`, `admin_confirmed`, `admin_rejected`, `room_blocked`, `room_completed`

### AuthLog
`login_success`, `login_fail`, `refresh_rotated`, `suspicious_activity`

### Retention
- Audit logs: ≥ 180 days
- Payment logs: ≥ 3 years
- Support tickets: ≥ 1 year

---

## Auth

### Mechanism
- JWT access + refresh token
- Access token: RS256 or ES256, TTL ≤ 15 min
- **Algorithm `none` is forbidden**
- Refresh token: stored hashed in DB, rotation on every use
- Reuse of old refresh token → revoke entire session (suspicious activity)
- Logout = revoke refresh token

### Registration / Login
- Email + password (min 8 chars, unique email)
- Google Sign-In (validate issuer/audience)
- Password reset via email
- Rate limit on login / forgot-password
- Temporary lockout after N failed attempts

### Admin Session
- 2FA required (TOTP)
- Session timeout: 15 min inactivity
- All logins logged

---

## Security Hard Rules

### RBAC
- All admin endpoints: log to AdminActionLog
- **IDOR protection**: ownership check in service layer for every `entity_id` param
- Unit/integration tests for IDOR required

### Input Validation
- Server-side only (Spring Validation on all DTOs)
- Parameterized queries only (no raw SQL)
- Sanitize user text fields (descriptions, reviews, ticket messages) on backend
- React default escaping; `dangerouslySetInnerHTML` **forbidden**

### Cookies / CSRF
- If refresh token in cookie: `SameSite` + `HttpOnly` + `Secure` + CSRF token
- If JWT only in `Authorization` header: still validate

### File Uploads (ticket attachments)
- Allowlist: `png`, `jpg`, `pdf`
- Check MIME type + magic bytes
- Size limit enforced
- Store in S3-compatible object storage
- Serve only via pre-signed URLs with expiry

### Race Conditions
- Optimistic locking (version column) on `rooms` and `room_members`
- Join must be atomic — cannot exceed `max_members` under parallel requests
- ACTIVE transition: one-time only
- Refund: idempotent
- Payment operations: `idempotency_key` required

### Encryption
- In transit: HTTPS/TLS 1.2+ (prefer 1.3), WSS only
- At rest: disk/volume encryption at infra level
- `connection_identifier` (TELECOM phone): application-level encryption or strict infra ACL
- Secrets (JWT keys, OAuth): Secret Manager only — **never in git or env files in repo**

### Rate Limiting
- Auth endpoints
- Room creation
- Room join
- Ticket message flood protection
- WebSocket message rate limit + forced disconnect on flood

### Browser Security Headers (required)
```
Content-Security-Policy (no inline scripts)
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Strict-Transport-Security (HSTS)
Referrer-Policy
```

### DB Security
- App uses least-privilege DB user (no superuser)
- Migrations run as separate user/process
- Backups + restore test required

---

## API Structure

```
/api/v1/auth
/api/v1/users
/api/v1/catalog/categories
/api/v1/catalog/services
/api/v1/rooms
/api/v1/rooms/{id}/members
/api/v1/reviews
/api/v1/support/tickets
/api/v1/support/tickets/{id}/messages
/api/v1/payments/intents
/api/v1/admin/rooms
/api/v1/admin/users
/api/v1/admin/disputes
/api/v1/admin/logs
/api/v1/admin/moderation-queue
```

### API Standards
- Swagger / OpenAPI required
- Unified error format: `{ code, message, details }`
- All list endpoints: pagination (limit/offset or cursor)
- Versioning: `/api/v1/`
- Heavy operations (notifications, risk scoring, email): async via queue/jobs

---

## Fraud / Risk Score (`user_risk_score`)

Increases on:
- Frequent disputes
- Mass room creation
- Exceeding join limits
- Suspicious activity patterns

High-risk users → manual moderation queue.

---

## Geolocation Module (MVP, minimal)
- Request user location (with consent)
- Display operator list with signal quality rating (approximate)
- Show disclaimer about approximate data
- No partner API integrations in MVP

---

## i18n
- **RU**: required for MVP
- KZ + EN: i18n structure and key files ready, translations can be added later
- Backend: no locale-specific business logic

---

## Definition of Done (MVP)

- [ ] Full flow: Create Room → Join → Pay → Owner grants access → Member confirms → ACTIVE
- [ ] Admin queue handles only exceptions (ModerationQueue, Disputes, SLA breaches)
- [ ] Payment layer scaffolded (no provider yet)
- [ ] OWASP Top 10 checklist passed
- [ ] Audit logs on all critical operations
- [ ] No secrets in git / images
- [ ] Rate limiting + anti-abuse active
- [ ] Backup/restore tested
- [ ] IDOR tests written and passing
- [ ] Threat modeling completed (STRIDE) covering: Auth, Room lifecycle, Payments, Admin panel, TELECOM logic
- [ ] All background jobs use outbox pattern
- [ ] Append-only enforced at DB role level for log tables
