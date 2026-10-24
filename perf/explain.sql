-- EXPLAIN ANALYZE of EcoPay's hot and high-volume queries on the throwaway perf database.
-- Run by run-local.sh (EXPLAIN=1) AFTER the k6 run: first inflates the big append-mostly tables to
-- :rows rows each so the planner faces production-like volumes, then explains the statements the
-- application issues (same SQL as the JPA/JdbcTemplate code). Writes run inside ROLLBACK.
\set ON_ERROR_STOP on
\pset pager off

-- ---------------------------------------------------------------- volume
INSERT INTO site_visit (visitor_id, visit_date, first_seen_at, last_seen_at, page_count,
                        is_authenticated, user_id, last_path)
SELECT gen_random_uuid(), current_date - (g % 120), now(), now(), 1 + g % 7, g % 5 = 0, NULL, '/'
  FROM generate_series(1, :rows) g
ON CONFLICT DO NOTHING;

-- TEST-NET-2 addresses and a dedicated domain: never collide with real k6 accounts or IPs.
INSERT INTO login_attempts (email, attempt_time, successful, ip_address)
SELECT 'seed' || (g % 50000) || '@volume.test', now() - (g % 43200) * interval '1 minute',
       g % 4 <> 0, '198.51.100.' || (g % 250)
  FROM generate_series(1, :rows) g;

WITH ids AS (SELECT array_agg(id) AS a FROM users)
INSERT INTO notifications (user_id, type, title, body, read_at, created_at)
SELECT ids.a[1 + g % array_length(ids.a, 1)], 'SYSTEM', 'volume', 'volume',
       CASE WHEN g % 3 = 0 THEN NULL ELSE now() END, now() - (g % 2000) * interval '1 hour'
  FROM generate_series(1, :rows) g, ids;

ANALYZE site_visit;
ANALYZE login_attempts;
ANALYZE notifications;

SELECT 'site_visit' AS table_name, count(*) FROM site_visit
UNION ALL SELECT 'login_attempts', count(*) FROM login_attempts
UNION ALL SELECT 'notifications', count(*) FROM notifications
UNION ALL SELECT 'rooms', count(*) FROM rooms
UNION ALL SELECT 'payment_intents', count(*) FROM payment_intents
UNION ALL SELECT 'payouts', count(*) FROM payouts;

SELECT min(id) AS busy_user FROM users \gset

-- ---------------------------------------------------------------- notifications
\echo '### notifications: unread badge (countByUserAndReadAtIsNull)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(n.id) FROM notifications n WHERE n.user_id = :busy_user AND n.read_at IS NULL;

\echo '### notifications: first page (findByUserOrderByCreatedAtDesc)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM notifications n WHERE n.user_id = :busy_user ORDER BY n.created_at DESC LIMIT 20;

-- ---------------------------------------------------------------- login_attempts
\echo '### login: account bucket (countByEmailAndSuccessfulFalseAndAttemptTimeAfter)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(a.id) FROM login_attempts a
 WHERE a.email = 'seed42@volume.test' AND a.successful = false
   AND a.attempt_time > now() - interval '15 minutes';

\echo '### login: IP bucket (countByIpAddressAndSuccessfulFalseAndAttemptTimeAfter)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(a.id) FROM login_attempts a
 WHERE a.ip_address = '198.51.100.42' AND a.successful = false
   AND a.attempt_time > now() - interval '15 minutes';

-- ---------------------------------------------------------------- site_visit
\echo '### analytics: atomic visit upsert (SiteVisitService)'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS)
INSERT INTO site_visit (visitor_id, visit_date, first_seen_at, last_seen_at, page_count,
                        is_authenticated, user_id, last_path)
VALUES ((SELECT visitor_id FROM site_visit WHERE visit_date = current_date LIMIT 1), current_date,
        now(), now(), 1, false, NULL, '/rooms')
ON CONFLICT (visitor_id, visit_date) DO UPDATE SET
  last_seen_at = EXCLUDED.last_seen_at, page_count = site_visit.page_count + 1,
  is_authenticated = site_visit.is_authenticated OR EXCLUDED.is_authenticated,
  user_id = COALESCE(site_visit.user_id, EXCLUDED.user_id),
  last_path = COALESCE(EXCLUDED.last_path, site_visit.last_path)
RETURNING page_count;
ROLLBACK;

\echo '### admin dashboard: 30-day unique visitors'
EXPLAIN (ANALYZE, BUFFERS)
SELECT COUNT(DISTINCT visitor_id) FROM site_visit WHERE visit_date >= current_date - 30;

\echo '### admin dashboard: MAU (authenticated)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT COUNT(DISTINCT user_id) FROM site_visit
 WHERE user_id IS NOT NULL AND visit_date >= current_date - 30;

-- ---------------------------------------------------------------- money schedulers
\echo '### payment reconciliation batch (findIdsForProviderReconciliation)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT p.id FROM payment_intents p
 WHERE p.status IN ('PENDING', 'RECONCILING', 'UNKNOWN') AND p.provider_name = 'freedompay'
   AND p.created_at < now() - interval '2 minutes'
   AND (p.last_reconciled_at IS NULL OR p.last_reconciled_at < now() - interval '2 minutes')
   AND coalesce(p.reconcile_attempts, 0) < 50
 ORDER BY p.created_at LIMIT 20;

\echo '### payout dispatcher (findDispatchable)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT p.* FROM payouts p
 WHERE (p.status IN ('PENDING', 'PENDING_METHOD') AND p.payout_batch_id IS NULL
        AND (p.release_at IS NULL OR p.release_at <= now())
        AND (p.next_retry_at IS NULL OR p.next_retry_at <= now()))
    OR (p.status = 'PROCESSING' AND p.payout_batch_id IS NULL AND p.provider_payout_id IS NULL
        AND p.lease_until IS NOT NULL AND p.lease_until <= now())
 ORDER BY p.created_at LIMIT 100;
