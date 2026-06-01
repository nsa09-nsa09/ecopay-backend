-- =========================================================
-- V7: indexes for admin /api/v1/admin/users list endpoint
-- search/sort by status, created_at, display_name, lower(email)
-- =========================================================

CREATE INDEX IF NOT EXISTS idx_users_status ON users(status);
CREATE INDEX IF NOT EXISTS idx_users_created_at ON users(created_at);
CREATE INDEX IF NOT EXISTS idx_users_display_name_lower ON users(LOWER(display_name));
CREATE INDEX IF NOT EXISTS idx_users_email_lower ON users(LOWER(email));
