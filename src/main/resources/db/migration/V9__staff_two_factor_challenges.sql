-- Two-factor authentication challenges for ADMIN / SUPPORT staff login.
-- A challenge holds a short-lived OTP that the staff member must confirm
-- after their password has been verified. Only the hashed code is stored.

CREATE TABLE IF NOT EXISTS staff_two_factor_challenges (
    id              VARCHAR(64)  PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash       VARCHAR(255) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER      NOT NULL DEFAULT 0,
    expires_at      TIMESTAMP    NOT NULL,
    used_at         TIMESTAMP,
    last_sent_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_staff_2fa_status
        CHECK (status IN ('PENDING', 'VERIFIED', 'EXPIRED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_staff_2fa_user_created
    ON staff_two_factor_challenges (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_staff_2fa_expires
    ON staff_two_factor_challenges (expires_at);
