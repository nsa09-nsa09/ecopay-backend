-- B7: track whether owner_verified was set by an admin override so phone-verification logic
-- doesn't quietly flip it back on a phone change. NULL = untouched by admin, TRUE/FALSE =
-- admin pinned it.

ALTER TABLE users ADD COLUMN IF NOT EXISTS owner_verified_by_admin BOOLEAN;
