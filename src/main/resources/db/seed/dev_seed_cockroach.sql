-- =========================================================
-- EcoPay DEV SEED — CockroachDB variant
--
-- Differences from dev_seed.sql (PostgreSQL/Neon):
--   * Explicit ids on every parent row. CockroachDB generates ids via
--     unique_rowid() (large, non-sequential), so we cannot rely on 1,2,3…
--     for the hardcoded foreign-key references. Supplying explicit small ids
--     keeps the refs consistent; app-generated rows still get huge unique_rowid
--     values, so there is no collision.
--   * TRUNCATE ... CASCADE (no RESTART IDENTITY — unsupported on CockroachDB).
-- =========================================================

TRUNCATE TABLE
    room_member_identifiers,
    room_members,
    refund_transactions,
    payment_transactions,
    payment_intents,
    support_messages,
    support_tickets,
    disputes,
    reviews,
    moderation_queue,
    user_risk_snapshots,
    room_event_log,
    admin_action_log,
    auth_log,
    outbox_events,
    rooms,
    tariff_plans,
    services,
    categories,
    password_reset_tokens,
    refresh_tokens,
    login_attempts,
    users
CASCADE;

-- =========================================================
-- USERS — пароль: 12345678
-- =========================================================

INSERT INTO users (id, email, password, display_name, public_id, status, reputation, email_verified, created_at)
VALUES
    (1, 'askar@test.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Askar', 'pub_askar', 'ACTIVE', 50, TRUE, NOW()),
    (2, 'maria@test.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Maria', 'pub_maria', 'ACTIVE', 50, TRUE, NOW()),
    (3, 'timur@test.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Timur', 'pub_timur', 'ACTIVE', 50, TRUE, NOW());

-- =========================================================
-- CATEGORIES
-- =========================================================

INSERT INTO categories (id, name, slug, is_active, sort_order, created_at)
VALUES
    (1, 'Streaming', 'streaming', TRUE, 1, NOW()),
    (2, 'Music',     'music',     TRUE, 2, NOW()),
    (3, 'Software',  'software',  TRUE, 3, NOW()),
    (4, 'Telecom',   'telecom',   TRUE, 4, NOW());

-- =========================================================
-- SERVICES
-- =========================================================

INSERT INTO services (id, category_id, name, slug, provider_type, access_type, is_active, created_at)
VALUES
    (1, 1, 'Netflix',         'netflix',         'DIGITAL',  'EMAIL', TRUE, NOW()),
    (2, 1, 'YouTube Premium', 'youtube-premium', 'DIGITAL',  'EMAIL', TRUE, NOW()),
    (3, 2, 'Spotify',         'spotify',         'DIGITAL',  'EMAIL', TRUE, NOW()),
    (4, 3, 'Microsoft 365',   'microsoft-365',   'DIGITAL',  'EMAIL', TRUE, NOW()),
    (5, 4, 'Beeline Family',  'beeline-family',  'OPERATOR', 'PHONE', TRUE, NOW()),
    (6, 4, 'Activ Family',    'activ-family',    'OPERATOR', 'PHONE', TRUE, NOW());

-- =========================================================
-- TARIFF PLANS
-- =========================================================

INSERT INTO tariff_plans (id, service_id, name, period_type, max_members, base_price_total, currency, connection_type, operator_rules, is_active, created_at)
VALUES
    (1, 1, 'Netflix Premium 4K',     'MONTHLY', 4, 7990,  'KZT', 'ACCOUNT_LINK', '{"profiles":4}',        TRUE, NOW()),
    (2, 2, 'YouTube Premium Family', 'MONTHLY', 5, 3290,  'KZT', 'ACCOUNT_LINK', '{"family":true}',       TRUE, NOW()),
    (3, 3, 'Spotify Family',         'MONTHLY', 6, 2690,  'KZT', 'ACCOUNT_LINK', '{"family":true}',       TRUE, NOW()),
    (4, 4, 'Microsoft 365 Family',   'YEARLY',  6, 34990, 'KZT', 'ACCOUNT_LINK', '{"onedrive":true}',     TRUE, NOW()),
    (5, 5, 'Beeline Family 5',       'MONTHLY', 5, 6500,  'KZT', 'ESIM',         '{"operator":"Beeline"}',TRUE, NOW()),
    (6, 6, 'Activ Family 4',         'MONTHLY', 4, 5900,  'KZT', 'SIM',          '{"operator":"Activ"}',  TRUE, NOW());

-- =========================================================
-- ROOMS
-- =========================================================

INSERT INTO rooms (id, owner_user_id, category_id, service_id, tariff_plan_id, room_type, verification_mode, status, title, description, max_members, price_total, price_per_member, currency, period_type, start_date, cancellation_policy, provider_name, tariff_name_snapshot, connection_type, operator_restrictions, operator_terms_confirmed, created_at)
VALUES
    (1, 1, 1, 1, 1, 'DIGITAL', 'RISK_BASED', 'OPEN', 'Netflix Premium Family', 'Ищем участников Netflix', 4, 7990, 1997.50, 'KZT', 'MONTHLY', NOW() + INTERVAL '2 days', 'Без возврата после старта', NULL, 'Netflix Premium 4K', 'ACCOUNT_LINK', NULL, FALSE, NOW()),
    (2, 2, 1, 2, 2, 'DIGITAL', 'RISK_BASED', 'OPEN', 'YouTube Premium Family', 'Подписка YouTube Premium', 5, 3290, 658.00, 'KZT', 'MONTHLY', NOW() + INTERVAL '1 day', 'До старта можно отменить', NULL, 'YouTube Premium Family', 'ACCOUNT_LINK', NULL, FALSE, NOW()),
    (3, 1, 4, 5, 5, 'TELECOM', 'RISK_BASED', 'OPEN', 'Beeline Family Plan', 'Набор участников Beeline', 5, 6500, 1300.00, 'KZT', 'MONTHLY', NOW() + INTERVAL '3 days', 'После активации возврата нет', 'Beeline', 'Beeline Family 5', 'ESIM', 'Только KZ номера', TRUE, NOW());

-- =========================================================
-- ROOM MEMBERS
-- =========================================================

INSERT INTO room_members (id, room_id, user_id, status, requires_admin_review, access_method, owner_access_confirmed_at, member_confirmed_at, activated_at, consent_accepted_at, created_at)
VALUES
    (1, 1, 2, 'ACTIVE',  FALSE, 'ACCOUNT_LINK', NOW(), NOW(), NOW(), NOW(), NOW()),
    (2, 3, 3, 'APPLIED', FALSE, NULL,           NULL,  NULL,  NULL,  NOW(), NOW());

-- =========================================================
-- ROOM MEMBER IDENTIFIERS
-- =========================================================

INSERT INTO room_member_identifiers (id, room_member_id, identifier_type, identifier_encrypted, identifier_masked, is_valid_format, created_at)
VALUES
    (1, 2, 'PHONE', 'TEST_ENCRYPTED_PHONE', '+7700*****11', TRUE, NOW());

-- =========================================================
-- PAYMENT INTENTS
-- =========================================================

INSERT INTO payment_intents (id, room_member_id, user_id, amount, currency, status, provider_name, external_payment_id, idempotency_key, expires_at, created_at, updated_at)
VALUES
    (1, 1, 2, 1997.50, 'KZT', 'SUCCESS', 'STUB', 'pay_demo_1', 'pi_demo_1', NOW() + INTERVAL '30 minutes', NOW(), NOW());

-- =========================================================
-- PAYMENT TRANSACTIONS
-- =========================================================

INSERT INTO payment_transactions (id, payment_intent_id, room_id, room_member_id, type, status, amount, currency, provider_name, external_transaction_id, reason, raw_payload, created_at, updated_at)
VALUES
    (1, 1, 1, 1, 'CHARGE', 'SUCCESS', 1997.50, 'KZT', 'STUB', 'tx_demo_1', 'Seed payment success', '{"source":"seed"}', NOW(), NOW());

-- =========================================================
-- LINK ROOM MEMBER TO PAYMENT
-- =========================================================

UPDATE room_members
SET payment_intent_id = 1, latest_payment_tx_id = 1, updated_at = NOW()
WHERE id = 1;

-- =========================================================
-- REFRESH TOKENS
-- =========================================================

INSERT INTO refresh_tokens (token, user_id, expires_at, created_at, revoked)
VALUES
    ('seed-refresh-askar-1', 1, NOW() + INTERVAL '7 days', NOW(), FALSE),
    ('seed-refresh-timur-1', 3, NOW() + INTERVAL '7 days', NOW(), FALSE);

-- =========================================================
-- AUTH LOG
-- =========================================================

INSERT INTO auth_log (event_id, user_id, email, event_type, ip_address, user_agent, created_at)
VALUES
    (gen_random_uuid(), 1, 'askar@test.com', 'LOGIN_SUCCESS',   '127.0.0.1', 'seed-script', NOW()),
    (gen_random_uuid(), 3, 'timur@test.com', 'REFRESH_SUCCESS', '127.0.0.1', 'seed-script', NOW());
