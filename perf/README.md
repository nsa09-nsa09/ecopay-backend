# EcoPay load tests

**Never point these at FreedomPay or at an environment whose payment provider is not the mock.**
`k6/ecopay-mixed.js` refuses to start unless `ECOPAY_PROVIDER_IS_MOCK=yes`, and `run-local.sh`
always starts the backend with `PAYMENTS_PROVIDER=mock`.

## Run

```bash
PROFILE=smoke ./perf/run-local.sh   # 20 VUs, ~2 min — functional check of every request type
PROFILE=load  ./perf/run-local.sh   # ramp 0→1000 VUs over 11 min, 10 min sustained, ramp down
```

Requirements: Docker, JDK 21 on `PATH`/`JAVA_HOME`, Maven dependencies already downloaded.
The script starts a throwaway PostgreSQL 16 (with `pg_stat_statements`), builds and starts the
backend (`APP_RATE_LIMIT_STORE=jdbc`, as in production), runs k6 in Docker, scrapes
`/actuator/prometheus` every 10 s and dumps the 20 most expensive statements. Output:
`perf/results/<timestamp>-<profile>/` (git-ignored): `k6.txt`, `k6-summary.json`,
`prometheus.txt` (Hikari, JVM heap/GC, CPU, Tomcat threads, `ecopay_*`), `slow-queries.txt`,
`app.log`.

## Traffic model

Each VU is one browser session with 2–10 s think time between page views:
home/catalog reads, catalog search, room list + room detail, authenticated profile/dashboard/joined
rooms/unread count (30 % also open notifications and payment history), analytics visit pings, and in
~3 % of iterations a room join + payment intent (mock provider). Every VU presents its own client IP
through the trusted reverse-proxy header, so per-IP limits behave as in production. Setup registers
members and owners, connects owner payout cards through the mock payout-card flow and creates rooms.
`APP_RATE_LIMIT_ROOM_JOIN_MAX` is raised in the harness only so join throttling does not mask
latency; all other limits are the production defaults.

## Thresholds (initial)

| Metric | Threshold |
|---|---|
| Unexpected HTTP failures (`http_req_failed`) | < 1 % |
| Reads p95 / p99 | < 800 ms / < 2000 ms |
| Auth p95 | < 1500 ms |
| Writes p95 | < 2000 ms |
| Checks | > 99 % |

Expected business outcomes (room full, already joined, throttled join, throttled visit ping) are
declared per request and do not count as failures.

## Interpreting results

A local run shares one machine between PostgreSQL, the JVM and k6, so absolute numbers are a lower
bound for a dedicated deployment, not a capacity guarantee. Record measured values in the release
report; do not extrapolate a user count from static inspection.

## Measured results (2026-10-06, local, single Windows workstation)

One machine ran k6 (Docker), PostgreSQL 16 (Docker) and the backend (`-Xmx1g`, Hikari max 20),
mock provider, `APP_RATE_LIMIT_STORE=jdbc`. Setup: 40 rooms, 300 members.

| Run | Requests | rps | `http_req_failed` | Read p95 / p99 | Auth p95 | Write p95 | Checks |
|---|---|---|---|---|---|---|---|
| `load`, 1000 VUs, 23 min (2nd run, token refresh fixed in script) | 769 130 | 531 | **0.02 %** (178) | 244 / 779 ms | 429 ms | 347 ms | 99.97 % |
| `load`, 1000 VUs (1st run) | 758 808 | 526 | 4.58 % | 328 / 834 ms | 307 ms | 466 ms | 95.42 % |

1st run failures were the k6 script itself: access tokens (15 min TTL) expired mid-run and the VU
never re-logged in. Fixed in the script, not in the backend.

2nd run: 0 × 401/429/500/503 seen by k6; 151 status-0 (`dial: i/o timeout` — TCP connect timeouts
through Docker Desktop NAT, 147 of them on login during the ramp). Server side: system CPU 1.0
(saturated by the three co-located processes), Hikari pending peaked at 180 with 13 acquire
timeouts (8 surfaced as `CannotCreateTransactionException` → 500 with an error reference).
Top DB cost: JDBC rate-limit counter upsert + read (0.17 / 0.08 ms mean, 339 k calls each),
user lookup by id, atomic `site_visit` upsert (0.22 ms mean). No statement averaged above 0.25 ms.

Conclusion: the thresholds pass at 1000 concurrent browsing sessions on this hardware, with pool
saturation at peak caused by CPU starvation. Re-run on staging hardware (separate DB host) before
quoting capacity; watch `hikaricp_connections_pending` and size `DB_POOL_MAX_SIZE` against the
CockroachDB node's connection budget.

## Query plans at volume (EXPLAIN=1)

`EXPLAIN=1 PROFILE=smoke ./perf/run-local.sh` additionally loads `auto_explain` (plans of real
statements ≥ 5 ms, 5 % sample → `auto-explain.log`) and, after k6, runs `perf/explain.sql`: inflates
`site_visit`, `login_attempts` and `notifications` to `EXPLAIN_ROWS` (default 300 000) rows each and
records `EXPLAIN (ANALYZE, BUFFERS)` of the hot statements → `explain.txt`.

Measured 2026-10-06 (300 k rows per table):

| Statement | Plan | Execution |
|---|---|---|
| Unread notification badge | Bitmap index scan `idx_notifications_user_unread` | 0.07 ms |
| Notification first page | Index scan `idx_notifications_user_created` | 0.05 ms |
| Login account bucket (count) | Index scan `idx_email_attempt` | 0.06 ms |
| Login IP bucket (count) | Bitmap index scan `idx_login_attempts_ip_time` | 0.06 ms |
| Atomic `site_visit` upsert | Unique index `uq_site_visit_visitor_date` (conflict arbiter) | 0.64 ms |
| Dashboard 30-day unique visitors | Bitmap index scan `idx_site_visit_visit_date`, 78 k rows | 32.8 ms |
| Dashboard MAU | Bitmap index scan `idx_site_visit_user_id` | 0.12 ms |
| Payment reconciliation batch | Partial index `uq_payment_intents_open_per_room_member` | 0.08 ms |
| Payout dispatcher | `idx_payouts_dispatch_due` available; tiny table → seq scan | 0.06 ms |

No statement crossed the 5 ms auto_explain threshold during the smoke traffic. The only
volume-sensitive query is the admin-only 30-day distinct-visitor count (linear in visits of the
window); acceptable for an on-demand admin page, a candidate for a daily rollup if traffic grows
100×. Money tables were small in this harness, so their plans are checked for index availability
rather than measured at volume.
