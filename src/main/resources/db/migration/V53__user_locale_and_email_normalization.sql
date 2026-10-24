-- =========================================================
-- V53 — Per-user locale for outgoing email + canonical email storage.
--
--   * users.locale: the language the account last used the app in ('ru',
--     'kk', 'en'). Transactional email is rendered in this language; NULL
--     falls back to the app default. Set on registration and refreshed
--     whenever the client sends Accept-Language on an authenticated call.
--
--   * Email is normalized (trim + lowercase) before every write and lookup
--     as of this release. Existing rows were written before that rule
--     existed, so backfill them — otherwise a user who registered as
--     "User@Gmail.com" could no longer log in once the lookup lowercases
--     their input.
--
--     The UNIQUE constraint on users.email means the backfill could collide
--     if two rows differ only by case. Those are duplicate signups for the
--     same mailbox; we lowercase the oldest (the one the user actually
--     confirmed and keeps using) and leave later duplicates untouched so
--     the migration cannot fail. They surface as "email already in use" on
--     next change attempt, which is the correct outcome.
-- =========================================================

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS locale VARCHAR(5);

UPDATE users u
SET email = lower(btrim(u.email))
WHERE u.email IS NOT NULL
  AND u.email <> lower(btrim(u.email))
  AND NOT EXISTS (
      SELECT 1
      FROM users other
      WHERE other.id <> u.id
        AND lower(btrim(other.email)) = lower(btrim(u.email))
        AND other.id < u.id
  );

UPDATE email_verification_tokens
SET pending_email = lower(btrim(pending_email))
WHERE pending_email IS NOT NULL
  AND pending_email <> lower(btrim(pending_email));

-- Login attempts are keyed by the identifier the caller typed. Lowercasing
-- them keeps the rate-limit window intact across the normalization switch,
-- so an attacker cannot reset their failed-attempt count by changing case.
UPDATE login_attempts
SET email = lower(btrim(email))
WHERE email IS NOT NULL
  AND email <> lower(btrim(email));
