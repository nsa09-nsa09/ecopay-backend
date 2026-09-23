CREATE TABLE user_reports (
    id BIGSERIAL PRIMARY KEY,
    reporter_user_id BIGINT NOT NULL REFERENCES users(id),
    target_user_id BIGINT NOT NULL REFERENCES users(id),
    category VARCHAR(20) NOT NULL CHECK (category IN ('FRAUD', 'ABUSE', 'HARASSMENT', 'SPAM', 'OTHER')),
    description VARCHAR(2000) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('OPEN', 'IN_REVIEW', 'RESOLVED', 'DISMISSED')),
    assigned_admin_id BIGINT REFERENCES users(id),
    resolution_note TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP,
    CONSTRAINT chk_user_reports_distinct_users CHECK (reporter_user_id <> target_user_id)
);
CREATE INDEX idx_user_reports_target_created ON user_reports(target_user_id, created_at DESC);
CREATE INDEX idx_user_reports_reporter_created ON user_reports(reporter_user_id, created_at DESC);
CREATE INDEX idx_user_reports_status_created ON user_reports(status, created_at DESC);
CREATE UNIQUE INDEX uq_user_reports_open_category ON user_reports(reporter_user_id, target_user_id, category)
    WHERE status IN ('OPEN', 'IN_REVIEW');
