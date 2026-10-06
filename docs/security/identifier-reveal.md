# Identifier Reveal Security

## Threat Model

Room member identifiers are sensitive PII. The main risks are revealing the wrong member, reusing an old successful payment after a full refund, exposing plaintext through logs/audit/cache, and staff using a stale or unrelated work context.

## Allowed Owner State

Owner reveal is allowed only when all checks pass:

- The actor is the current owner of the exact room.
- The room and membership are not deleted.
- The membership belongs to the requested room.
- The member account is not banned.
- Membership status is `PENDING` or `ACTIVE`.
- A successful `CHARGE` transaction exists for that membership.
- Pending/success refunds do not fully consume that charge amount.
- `reasonCode` is one of `PROVIDE_SERVICE_ACCESS`, `RETRY_SERVICE_INVITE`, or `RESOLVE_ACCESS_CONFIGURATION`.

## Allowed Staff Context

Staff reveal requires an active context for the same `roomMember`.

- `MODERATION`: `OPEN` or `IN_REVIEW`, ADMIN only, assigned to current admin.
- `SUPPORT`: `OPEN`, `IN_PROGRESS`, `WAITING_USER`, or `ESCALATED`, assigned to current staff user.
- `DISPUTE`: `OPEN` or `UNDER_REVIEW`, assigned to current staff user.

Unassigned contexts must be claimed before reveal. SUPPORT cannot use moderation context.

## Audit Fields

Every reveal attempt is written to `identifier_reveal_audit` with typed columns:

- actor user ID and actor role
- room ID and room member ID
- staff context type and ID, when present
- reason code
- outcome: `SUCCESS`, `DENIED`, `RATE_LIMITED`, or `DECRYPTION_FAILED`
- request/correlation ID
- normalized client IP
- user agent capped to 255 characters
- timestamp

The table is append-only at the application and database trigger level.

## Never Log

Never persist or log plaintext identifier, encrypted identifier, full email/phone/account, authorization header, cookie, access token, refresh token, or reveal response body.

## Frontend TTL

The owner UI keeps a revealed value only in the current modal/component state. It is cleared on modal close, unmount, navigation, failed retry, and after the backend TTL, defaulting to 30 seconds. Copy is a separate explicit click.

## Audit Verification

To verify a reveal, query `identifier_reveal_audit` by `room_member_id` and `created_at`, then confirm the row has the expected actor, context, reason code, and outcome. Do not query generic JSON event logs for plaintext reveal data.

## Encryption Key Rotation

New identifier ciphertext is written as `v1:gcm:<base64>`. The payload uses AES-GCM with a random 96-bit nonce and a 256-bit key from `APP_SECURITY_FIELD_ENCRYPTION_KEY`.

Rotation procedure:

1. Add support for the next key version in `FieldEncryptionService`.
2. Deploy read support for both old and new versions.
3. Switch writes to the new version.
4. Backfill records in a controlled job that never logs plaintext.
5. Remove old-key decrypt support only after all records and backups are outside the required retention window.
