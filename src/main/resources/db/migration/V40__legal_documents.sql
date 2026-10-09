-- =========================================================
-- V40 — Legal documents (Terms of Service, Privacy consent).
--
-- One row per doc_type; text lives per-language (kz/ru/en). Initial rows
-- are NOT seeded via SQL because the source text is a long multi-paragraph
-- document — LegalDocumentSeeder loads the classpath resource files at
-- application start-up (idempotent: existing rows are never overwritten).
--
-- Also extends chk_admin_action_log_action_type to permit
-- LEGAL_DOCUMENT_UPDATED audit rows. The full pre-existing value list is
-- copied verbatim from V34 (the last migration that redefined it).
-- =========================================================

CREATE TABLE IF NOT EXISTS legal_documents (
    id          BIGSERIAL     PRIMARY KEY,
    doc_type    VARCHAR(32)   NOT NULL UNIQUE,
    version     INTEGER       NOT NULL DEFAULT 1,
    title_kz    TEXT,
    title_ru    TEXT,
    title_en    TEXT,
    body_kz     TEXT,
    body_ru     TEXT,
    body_en     TEXT,
    updated_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_by  BIGINT        REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_legal_documents_doc_type CHECK (doc_type IN ('TERMS', 'PRIVACY'))
);

ALTER TABLE admin_action_log
    DROP CONSTRAINT IF EXISTS chk_admin_action_log_action_type;

ALTER TABLE admin_action_log
    ADD CONSTRAINT chk_admin_action_log_action_type
    CHECK (action_type IN (
        'ACCESS_CONFIRMED',
        'ACCESS_REJECTED',
        'ROOM_BLOCKED',
        'USER_BANNED',
        'USER_UNBANNED',
        'USER_CREATED',
        'USER_ROLE_CHANGED',
        'REFUND_INITIATED',
        'REFUND_APPROVED',
        'REFUND_REJECTED',
        'DISPUTE_RESOLVED',
        'BATCH_CONFIRM',
        'OWNER_VERIFICATION_CHANGED',
        'CATEGORY_CREATED',
        'CATEGORY_UPDATED',
        'CATEGORY_DELETED',
        'SERVICE_CREATED',
        'SERVICE_UPDATED',
        'SERVICE_DELETED',
        'TARIFF_CREATED',
        'TARIFF_UPDATED',
        'TARIFF_DELETED',
        'TESTIMONIAL_FEATURED',
        'TESTIMONIAL_UNFEATURED',
        'TESTIMONIAL_EDITED',
        'TESTIMONIAL_DELETED',
        'SITE_CONTENT_UPDATED',
        'FEEDBACK_STATUS_CHANGED',
        'FEEDBACK_NOTE_UPDATED',
        'NEWS_CREATED',
        'NEWS_UPDATED',
        'NEWS_DELETED',
        'LEGAL_DOCUMENT_UPDATED'
    ));
