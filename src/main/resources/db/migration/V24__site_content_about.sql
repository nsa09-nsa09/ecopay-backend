-- =========================================================
-- V24 — Site content (About Us) singleton row + new admin action type.
--
--   * Adds a single-row site_content table for the public "О нас" page
--     content that admins can edit from /api/v1/admin/site/about.
--   * Seeds id=1 with the original static copy so GET /api/v1/site/about
--     returns something sensible on a fresh deployment.
--   * Extends chk_admin_action_log_action_type to allow
--     SITE_CONTENT_UPDATED audit-log writes.
-- =========================================================

CREATE TABLE site_content (
    id              BIGINT       PRIMARY KEY,
    company_name    VARCHAR(255) NOT NULL,
    title           VARCHAR(255) NOT NULL,
    mission         TEXT,
    description     TEXT,
    contact_email   VARCHAR(255),
    contact_phone   VARCHAR(64),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by      BIGINT       REFERENCES users(id) ON DELETE SET NULL,
    -- Hard-cap to a single row at the DB level: only id = 1 is ever valid.
    CONSTRAINT chk_site_content_singleton CHECK (id = 1)
);

INSERT INTO site_content (id, company_name, title, mission, description, contact_email, contact_phone)
VALUES (
    1,
    'EcoPay',
    'О EcoPay',
    'EcoPay делает семейные тарифы операторов связи доступными для всех в Казахстане. Присоединяйтесь к общей комнате, разделяйте оплату и экономьте до 70% от ежемесячного счёта — без контрактов и лишних хлопот.',
    'Мы соединяем людей, желающих разделить семейные тарифы ведущих операторов Казахстана: Beeline, Activ, Altel, Tele2 и Kcell. Наша платформа обеспечивает обработку платежей, верификацию и поддержку, чтобы вы могли сосредоточиться на экономии.',
    'support@ecopay.kz',
    '+7 747 226 6885'
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
        'SITE_CONTENT_UPDATED'
    ));
