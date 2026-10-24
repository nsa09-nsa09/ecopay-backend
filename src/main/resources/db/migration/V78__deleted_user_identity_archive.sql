CREATE TABLE deleted_user_identity_archive (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE REFERENCES users(id),
    email_encrypted TEXT,
    phone_encrypted TEXT,
    email_masked VARCHAR(255),
    phone_masked VARCHAR(50),
    slug_at_deletion VARCHAR(30),
    display_name_at_deletion VARCHAR(255),
    deleted_at TIMESTAMP NOT NULL,
    archived_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER trg_deleted_user_identity_archive_append_only
    BEFORE UPDATE OR DELETE ON deleted_user_identity_archive
    FOR EACH ROW EXECUTE FUNCTION block_audit_log_modify();
