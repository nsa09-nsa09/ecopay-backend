-- =========================================================
-- Bölip Töle / EcoSplit schema
-- DATABASE: splitup
-- PostgreSQL 14+
-- V1 INIT SCHEMA
-- =========================================================

-- =========================================================
-- AUTH TABLES
-- =========================================================

CREATE TABLE IF NOT EXISTS users (
                                     id              BIGSERIAL PRIMARY KEY,
                                     email           VARCHAR(255) NOT NULL UNIQUE,
    password        VARCHAR(255) NOT NULL,
    display_name    VARCHAR(255) NOT NULL,
    avatar          VARCHAR(500),
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    reputation      INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP,

    CONSTRAINT chk_users_status
    CHECK (status IN ('ACTIVE', 'BANNED'))
    );

CREATE INDEX IF NOT EXISTS idx_email ON users(email);

CREATE TABLE IF NOT EXISTS refresh_tokens (
                                              id              BIGSERIAL PRIMARY KEY,
                                              token           VARCHAR(255) NOT NULL UNIQUE,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at      TIMESTAMP NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked         BOOLEAN NOT NULL DEFAULT FALSE
    );

CREATE INDEX IF NOT EXISTS idx_token ON refresh_tokens(token);
CREATE INDEX IF NOT EXISTS idx_user_id ON refresh_tokens(user_id);

CREATE TABLE IF NOT EXISTS password_reset_tokens (
                                                     id              BIGSERIAL PRIMARY KEY,
                                                     token           VARCHAR(255) NOT NULL UNIQUE,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at      TIMESTAMP NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    used            BOOLEAN NOT NULL DEFAULT FALSE
    );

CREATE INDEX IF NOT EXISTS idx_reset_token ON password_reset_tokens(token);

CREATE TABLE IF NOT EXISTS login_attempts (
                                              id              BIGSERIAL PRIMARY KEY,
                                              email           VARCHAR(255) NOT NULL,
    attempt_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    successful      BOOLEAN NOT NULL
    );

CREATE INDEX IF NOT EXISTS idx_email_attempt ON login_attempts(email, attempt_time);

-- =========================================================
-- CATALOG
-- =========================================================

CREATE TABLE IF NOT EXISTS categories (
                                          id              BIGSERIAL PRIMARY KEY,
                                          name            VARCHAR(100) NOT NULL,
    slug            VARCHAR(120) NOT NULL UNIQUE,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order      INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP
    );

CREATE TABLE IF NOT EXISTS services (
                                        id              BIGSERIAL PRIMARY KEY,
                                        category_id     BIGINT NOT NULL REFERENCES categories(id),
    name            VARCHAR(120) NOT NULL,
    slug            VARCHAR(120) NOT NULL UNIQUE,
    provider_type   VARCHAR(20) NOT NULL,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP,

    CONSTRAINT chk_services_provider_type
    CHECK (provider_type IN ('OPERATOR', 'ISP', 'DIGITAL'))
    );

CREATE INDEX IF NOT EXISTS idx_services_category_id ON services(category_id);

CREATE TABLE IF NOT EXISTS tariff_plans (
                                            id                  BIGSERIAL PRIMARY KEY,
                                            service_id          BIGINT NOT NULL REFERENCES services(id),
    name                VARCHAR(150) NOT NULL,
    period_type         VARCHAR(20) NOT NULL,
    max_members         INTEGER NOT NULL,
    base_price_total    NUMERIC(12,2),
    currency            VARCHAR(10) NOT NULL DEFAULT 'KZT',
    connection_type     VARCHAR(20),
    operator_rules      JSONB,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP,

    CONSTRAINT chk_tariff_plans_max_members
    CHECK (max_members >= 2),

    CONSTRAINT chk_tariff_plans_period_type
    CHECK (period_type IN ('MONTHLY', 'YEARLY', 'OTHER')),

    CONSTRAINT chk_tariff_plans_connection_type
    CHECK (
              connection_type IS NULL
              OR connection_type IN ('SIM', 'ESIM', 'ACCOUNT_LINK', 'OTHER')
    )
    );

CREATE INDEX IF NOT EXISTS idx_tariff_plans_service_id ON tariff_plans(service_id);

-- =========================================================
-- ROOMS
-- =========================================================

CREATE TABLE IF NOT EXISTS rooms (
                                     id                          BIGSERIAL PRIMARY KEY,
                                     owner_user_id               BIGINT NOT NULL REFERENCES users(id),
    category_id                 BIGINT REFERENCES categories(id),
    service_id                  BIGINT NOT NULL REFERENCES services(id),
    tariff_plan_id              BIGINT REFERENCES tariff_plans(id),

    room_type                   VARCHAR(20) NOT NULL,
    verification_mode           VARCHAR(30) NOT NULL DEFAULT 'RISK_BASED',
    status                      VARCHAR(30) NOT NULL DEFAULT 'OPEN',

    title                       VARCHAR(150) NOT NULL,
    description                 TEXT,
    max_members                 INTEGER NOT NULL,
    price_total                 NUMERIC(12,2),
    price_per_member            NUMERIC(12,2),
    currency                    VARCHAR(10) NOT NULL DEFAULT 'KZT',
    period_type                 VARCHAR(20) NOT NULL,
    start_date                  TIMESTAMP NOT NULL,
    cancellation_policy         TEXT,

    provider_name               VARCHAR(120),
    tariff_name_snapshot        VARCHAR(150),
    connection_type             VARCHAR(20),
    operator_restrictions       TEXT,
    operator_terms_confirmed    BOOLEAN NOT NULL DEFAULT FALSE,

    ready_for_verification_at   TIMESTAMP,
    completed_at                TIMESTAMP,
    blocked_at                  TIMESTAMP,
    block_reason                TEXT,

    version                     BIGINT NOT NULL DEFAULT 0,
    created_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP,
    deleted_at                  TIMESTAMP,

    CONSTRAINT chk_rooms_max_members
    CHECK (max_members >= 2),

    CONSTRAINT chk_rooms_price
    CHECK (price_total IS NOT NULL OR price_per_member IS NOT NULL),

    CONSTRAINT chk_rooms_room_type
    CHECK (room_type IN ('DIGITAL', 'TELECOM')),

    CONSTRAINT chk_rooms_verification_mode
    CHECK (verification_mode IN ('AUTO', 'ADMIN_REQUIRED', 'RISK_BASED')),

    CONSTRAINT chk_rooms_status
    CHECK (status IN ('OPEN', 'IN_VERIFICATION', 'ACTIVE', 'COMPLETED', 'CANCELLED', 'BLOCKED')),

    CONSTRAINT chk_rooms_period_type
    CHECK (period_type IN ('MONTHLY', 'YEARLY', 'OTHER')),

    CONSTRAINT chk_rooms_connection_type
    CHECK (
              connection_type IS NULL
              OR connection_type IN ('SIM', 'ESIM', 'ACCOUNT_LINK', 'OTHER')
    ),

    CONSTRAINT chk_rooms_telecom_fields
    CHECK (
              room_type <> 'TELECOM'
              OR (
              provider_name IS NOT NULL
              AND connection_type IS NOT NULL
              AND operator_terms_confirmed = TRUE
                 )
    )
    );

CREATE INDEX IF NOT EXISTS idx_rooms_status_start_date
    ON rooms(status, start_date);

CREATE INDEX IF NOT EXISTS idx_rooms_owner_created_at
    ON rooms(owner_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_rooms_service_status_start_date
    ON rooms(service_id, status, start_date);

-- =========================================================
-- ROOM MEMBERS
-- =========================================================

CREATE TABLE IF NOT EXISTS room_members (
                                            id                          BIGSERIAL PRIMARY KEY,
                                            room_id                     BIGINT NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    user_id                     BIGINT NOT NULL REFERENCES users(id),
    status                      VARCHAR(40) NOT NULL DEFAULT 'APPLIED',

    requires_admin_review       BOOLEAN NOT NULL DEFAULT FALSE,
    access_method               VARCHAR(50),
    owner_access_confirmed_at   TIMESTAMP,
    member_confirmed_at         TIMESTAMP,
    activated_at                TIMESTAMP,
    rejected_at                 TIMESTAMP,
    ended_at                    TIMESTAMP,

    payment_intent_id           BIGINT,
    latest_payment_tx_id        BIGINT,

    consent_accepted_at         TIMESTAMP,
    version                     BIGINT NOT NULL DEFAULT 0,
    created_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP,
    deleted_at                  TIMESTAMP,

    CONSTRAINT chk_room_members_status
    CHECK (status IN (
           'APPLIED',
           'PENDING',
           'ACTIVE',
           'REJECTED',
           'CANCELLED_BEFORE_PAYMENT',
           'BLOCKED_BY_ADMIN'
                     ))
    );

CREATE INDEX IF NOT EXISTS idx_room_members_room_status
    ON room_members(room_id, status);

CREATE INDEX IF NOT EXISTS idx_room_members_user_created_at
    ON room_members(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_room_members_requires_admin_review
    ON room_members(requires_admin_review, status);

CREATE UNIQUE INDEX IF NOT EXISTS uq_room_member_active
    ON room_members(room_id, user_id)
    WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS room_member_identifiers (
                                                       id                      BIGSERIAL PRIMARY KEY,
                                                       room_member_id          BIGINT NOT NULL UNIQUE REFERENCES room_members(id) ON DELETE CASCADE,
    identifier_type         VARCHAR(20) NOT NULL,
    identifier_encrypted    TEXT NOT NULL,
    identifier_masked       VARCHAR(50) NOT NULL,
    is_valid_format         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP,

    CONSTRAINT chk_room_member_identifiers_type
    CHECK (identifier_type IN ('PHONE', 'ACCOUNT', 'SIM', 'ESIM'))
    );

-- =========================================================
-- PAYMENTS
-- =========================================================

CREATE TABLE IF NOT EXISTS payment_intents (
                                               id                      BIGSERIAL PRIMARY KEY,
                                               room_member_id          BIGINT NOT NULL REFERENCES room_members(id),
    user_id                 BIGINT NOT NULL REFERENCES users(id),
    amount                  NUMERIC(12,2) NOT NULL,
    currency                VARCHAR(10) NOT NULL DEFAULT 'KZT',
    status                  VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    provider_name           VARCHAR(50),
    external_payment_id     VARCHAR(150),
    idempotency_key         VARCHAR(100) NOT NULL UNIQUE,
    expires_at              TIMESTAMP,
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP,

    CONSTRAINT chk_payment_intents_status
    CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'CANCELLED'))
    );

CREATE INDEX IF NOT EXISTS idx_payment_intents_room_member_created_at
    ON payment_intents(room_member_id, created_at DESC);

CREATE TABLE IF NOT EXISTS payment_transactions (
                                                    id                      BIGSERIAL PRIMARY KEY,
                                                    payment_intent_id       BIGINT NOT NULL REFERENCES payment_intents(id),
    room_id                 BIGINT REFERENCES rooms(id),
    room_member_id          BIGINT REFERENCES room_members(id),
    type                    VARCHAR(20) NOT NULL DEFAULT 'CHARGE',
    status                  VARCHAR(30) NOT NULL,
    amount                  NUMERIC(12,2) NOT NULL,
    currency                VARCHAR(10) NOT NULL DEFAULT 'KZT',
    provider_name           VARCHAR(50),
    external_transaction_id VARCHAR(150),
    reason                  VARCHAR(255),
    raw_payload             JSONB,
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP,

    CONSTRAINT chk_payment_transactions_type
    CHECK (type IN ('CHARGE', 'REFUND')),

    CONSTRAINT chk_payment_transactions_status
    CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'REFUNDED_PARTIAL', 'REFUNDED_FULL'))
    );

CREATE INDEX IF NOT EXISTS idx_payment_transactions_intent_status
    ON payment_transactions(payment_intent_id, status);

CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_transactions_external_transaction_id
    ON payment_transactions(external_transaction_id)
    WHERE external_transaction_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS refund_transactions (
                                                   id                      BIGSERIAL PRIMARY KEY,
                                                   payment_transaction_id  BIGINT NOT NULL REFERENCES payment_transactions(id),
    dispute_id              BIGINT,
    admin_user_id           BIGINT REFERENCES users(id),
    status                  VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    amount                  NUMERIC(12,2) NOT NULL,
    currency                VARCHAR(10) NOT NULL DEFAULT 'KZT',
    reason                  VARCHAR(255) NOT NULL,
    idempotency_key         VARCHAR(100) NOT NULL UNIQUE,
    provider_refund_id      VARCHAR(150),
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP,

    CONSTRAINT chk_refund_transactions_status
    CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED'))
    );

-- =========================================================
-- SUPPORT
-- =========================================================

CREATE TABLE IF NOT EXISTS support_tickets (
                                               id                      BIGSERIAL PRIMARY KEY,
                                               user_id                 BIGINT NOT NULL REFERENCES users(id),
    room_id                 BIGINT REFERENCES rooms(id),
    room_member_id          BIGINT REFERENCES room_members(id),
    subject                 VARCHAR(200) NOT NULL,
    topic                   VARCHAR(50) NOT NULL,
    status                  VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    priority                VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    assigned_admin_id       BIGINT REFERENCES users(id),
    escalated_to_dispute    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP,
    closed_at               TIMESTAMP,

    CONSTRAINT chk_support_tickets_status
    CHECK (status IN ('OPEN', 'IN_PROGRESS', 'WAITING_USER', 'ESCALATED', 'CLOSED'))
    );

CREATE INDEX IF NOT EXISTS idx_support_tickets_user_status_created_at
    ON support_tickets(user_id, status, created_at DESC);

CREATE TABLE IF NOT EXISTS support_messages (
                                                id                      BIGSERIAL PRIMARY KEY,
                                                ticket_id               BIGINT NOT NULL REFERENCES support_tickets(id) ON DELETE CASCADE,
    sender_user_id          BIGINT NOT NULL REFERENCES users(id),
    sender_role             VARCHAR(20) NOT NULL,
    message                 TEXT NOT NULL,
    attachment_url          TEXT,
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_support_messages_sender_role
    CHECK (sender_role IN ('USER', 'SUPPORT', 'ADMIN', 'SYSTEM'))
    );

CREATE INDEX IF NOT EXISTS idx_support_messages_ticket_created_at
    ON support_messages(ticket_id, created_at DESC);

-- =========================================================
-- DISPUTES
-- =========================================================

CREATE TABLE IF NOT EXISTS disputes (
                                        id                      BIGSERIAL PRIMARY KEY,
                                        room_id                 BIGINT NOT NULL REFERENCES rooms(id),
    room_member_id          BIGINT REFERENCES room_members(id),
    ticket_id               BIGINT REFERENCES support_tickets(id),
    opened_by_user_id       BIGINT NOT NULL REFERENCES users(id),
    assigned_admin_id       BIGINT REFERENCES users(id),

    reason_code             VARCHAR(50) NOT NULL,
    description             TEXT,
    status                  VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    decision                VARCHAR(50),
    decision_comment        TEXT,

    resolved_at             TIMESTAMP,
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP,

    CONSTRAINT chk_disputes_status
    CHECK (status IN ('OPEN', 'UNDER_REVIEW', 'RESOLVED', 'REJECTED'))
    );

CREATE INDEX IF NOT EXISTS idx_disputes_status_created_at
    ON disputes(status, created_at);

ALTER TABLE refund_transactions
DROP CONSTRAINT IF EXISTS fk_refund_dispute;

ALTER TABLE refund_transactions
    ADD CONSTRAINT fk_refund_dispute
        FOREIGN KEY (dispute_id) REFERENCES disputes(id);

-- =========================================================
-- REVIEWS / REPUTATION
-- =========================================================

CREATE TABLE IF NOT EXISTS reviews (
                                       id                      BIGSERIAL PRIMARY KEY,
                                       room_id                 BIGINT NOT NULL REFERENCES rooms(id),
    author_user_id          BIGINT NOT NULL REFERENCES users(id),
    target_user_id          BIGINT NOT NULL REFERENCES users(id),
    rating                  INTEGER NOT NULL,
    text                    TEXT,
    status                  VARCHAR(20) NOT NULL DEFAULT 'VISIBLE',
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP,
    hidden_by_admin_id      BIGINT REFERENCES users(id),
    hidden_reason           TEXT,

    CONSTRAINT chk_reviews_rating
    CHECK (rating BETWEEN 1 AND 5),

    CONSTRAINT uq_review_once_per_room
    UNIQUE (room_id, author_user_id, target_user_id),

    CONSTRAINT chk_reviews_status
    CHECK (status IN ('VISIBLE', 'HIDDEN'))
    );

CREATE INDEX IF NOT EXISTS idx_reviews_target_created_at
    ON reviews(target_user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS user_risk_snapshots (
                                                   id              BIGSERIAL PRIMARY KEY,
                                                   user_id         BIGINT NOT NULL REFERENCES users(id),
    risk_score      NUMERIC(10,2) NOT NULL,
    reason_json     JSONB,
    calculated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

CREATE TABLE IF NOT EXISTS moderation_queue (
                                                id                      BIGSERIAL PRIMARY KEY,
                                                entity_type             VARCHAR(30) NOT NULL,
    entity_id               BIGINT NOT NULL,
    room_id                 BIGINT REFERENCES rooms(id),
    room_member_id          BIGINT REFERENCES room_members(id),
    reason_code             VARCHAR(50) NOT NULL,
    risk_score              NUMERIC(10,2) NOT NULL DEFAULT 0,
    status                  VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    assigned_admin_id       BIGINT REFERENCES users(id),
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP,

    CONSTRAINT chk_moderation_queue_status
    CHECK (status IN ('OPEN', 'IN_REVIEW', 'RESOLVED', 'REJECTED'))
    );

CREATE INDEX IF NOT EXISTS idx_moderation_queue_status_created_at
    ON moderation_queue(status, created_at DESC);

-- =========================================================
-- LOGS / AUDIT / OUTBOX
-- =========================================================

CREATE TABLE IF NOT EXISTS admin_action_log (
                                                id                  BIGSERIAL PRIMARY KEY,
                                                event_id            UUID NOT NULL UNIQUE,
                                                correlation_id      UUID,
                                                idempotency_key     VARCHAR(100),

    admin_user_id       BIGINT NOT NULL REFERENCES users(id),
    action_type         VARCHAR(30) NOT NULL,
    entity_type         VARCHAR(30) NOT NULL,
    entity_id           BIGINT NOT NULL,
    reason              TEXT,
    old_state           JSONB,
    new_state           JSONB,
    ip_address          VARCHAR(255),
    user_agent          TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_admin_action_log_action_type
    CHECK (action_type IN (
           'ACCESS_CONFIRMED',
           'ACCESS_REJECTED',
           'ROOM_BLOCKED',
           'USER_BANNED',
           'USER_UNBANNED',
           'REFUND_INITIATED',
           'REFUND_APPROVED',
           'REFUND_REJECTED',
           'DISPUTE_RESOLVED',
           'BATCH_CONFIRM'
                          ))
    );

CREATE INDEX IF NOT EXISTS idx_admin_action_log_created_at
    ON admin_action_log(created_at DESC);

CREATE TABLE IF NOT EXISTS room_event_log (
                                              id                  BIGSERIAL PRIMARY KEY,
                                              event_id            UUID NOT NULL UNIQUE,
                                              correlation_id      UUID,
                                              idempotency_key     VARCHAR(100),

    actor_user_id       BIGINT REFERENCES users(id),
    actor_role          VARCHAR(20) NOT NULL,
    room_id             BIGINT NOT NULL REFERENCES rooms(id),
    room_member_id      BIGINT REFERENCES room_members(id),
    event_type          VARCHAR(50) NOT NULL,
    old_state           JSONB,
    new_state           JSONB,
    ip_address          VARCHAR(255),
    user_agent          TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

CREATE INDEX IF NOT EXISTS idx_room_event_log_room_created_at
    ON room_event_log(room_id, created_at DESC);

CREATE TABLE IF NOT EXISTS auth_log (
                                        id              BIGSERIAL PRIMARY KEY,
                                        event_id        UUID NOT NULL UNIQUE,
                                        user_id         BIGINT REFERENCES users(id),
    email           VARCHAR(255),
    event_type      VARCHAR(50) NOT NULL,
    ip_address      VARCHAR(255),
    user_agent      TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

CREATE INDEX IF NOT EXISTS idx_auth_log_user_created_at
    ON auth_log(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS outbox_events (
                                             id              BIGSERIAL PRIMARY KEY,
                                             event_type      VARCHAR(100) NOT NULL,
    aggregate_type  VARCHAR(50) NOT NULL,
    aggregate_id    BIGINT NOT NULL,
    payload         JSONB NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    available_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at    TIMESTAMP,
    retry_count     INTEGER NOT NULL DEFAULT 0,
    last_error      TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_outbox_events_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED'))
    );

CREATE INDEX IF NOT EXISTS idx_outbox_events_status_available_at
    ON outbox_events(status, available_at);

-- =========================================================
-- PARTIAL UNIQUE GUARD
-- =========================================================

CREATE UNIQUE INDEX IF NOT EXISTS uq_support_open_access_issue_per_member
    ON support_tickets(room_member_id)
    WHERE room_member_id IS NOT NULL
    AND topic = 'ACCESS_ISSUE'
    AND status IN ('OPEN', 'IN_PROGRESS', 'ESCALATED');

-- =========================================================
-- LATE FOREIGN KEYS
-- =========================================================

ALTER TABLE room_members
DROP CONSTRAINT IF EXISTS fk_room_members_payment_intent;

ALTER TABLE room_members
    ADD CONSTRAINT fk_room_members_payment_intent
        FOREIGN KEY (payment_intent_id) REFERENCES payment_intents(id);

ALTER TABLE room_members
DROP CONSTRAINT IF EXISTS fk_room_members_latest_payment_tx;

ALTER TABLE room_members
    ADD CONSTRAINT fk_room_members_latest_payment_tx
        FOREIGN KEY (latest_payment_tx_id) REFERENCES payment_transactions(id);