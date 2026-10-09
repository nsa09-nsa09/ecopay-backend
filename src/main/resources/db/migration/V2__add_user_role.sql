ALTER TABLE users
    ADD COLUMN IF NOT EXISTS role VARCHAR(20);

UPDATE users
SET role = 'USER'
WHERE role IS NULL;

ALTER TABLE users
    ALTER COLUMN role SET NOT NULL;

ALTER TABLE users
    ALTER COLUMN role SET DEFAULT 'USER';

-- CockroachDB does not support anonymous DO blocks; add the constraint directly.
-- Flyway runs each migration once, so the existence guard isn't needed.
ALTER TABLE users
    ADD CONSTRAINT chk_users_role
        CHECK (role IN ('USER', 'ADMIN', 'SUPPORT'));