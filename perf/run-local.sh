#!/usr/bin/env bash
# Repeatable local load test for EcoPay with the MOCK payment provider only.
#
#   PROFILE=smoke ./perf/run-local.sh     # ~2 min, 20 VUs
#   PROFILE=load  ./perf/run-local.sh     # ramp to 1000 VUs, 10 min sustained
#   EXPLAIN=1 PROFILE=smoke ./perf/run-local.sh   # + auto_explain plans and perf/explain.sql
#
# Starts a throwaway PostgreSQL 16 (pg_stat_statements on), builds and starts the backend with
# PAYMENTS_PROVIDER=mock, runs k6 in Docker, scrapes /actuator/prometheus every 10 s and dumps the
# slowest statements. Results land in perf/results/<timestamp>-<profile>/.
# It never contacts FreedomPay: the provider is forced to mock and k6 refuses otherwise.
set -euo pipefail

PROFILE="${PROFILE:-smoke}"
PORT="${PORT:-18080}"
PG_PORT="${PG_PORT:-55432}"
STAMP="$(date +%Y%m%d-%H%M%S)"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/perf/results/$STAMP-$PROFILE"
PG_NAME="ecopay-perf-pg-$STAMP"
EXPLAIN="${EXPLAIN:-0}"
EXPLAIN_ROWS="${EXPLAIN_ROWS:-300000}"
PG_EXTRA=()
if [ "$EXPLAIN" = "1" ]; then
  # Real statements with real parameters: plans of anything slower than 5 ms plus a 5 % sample.
  PG_EXTRA=(-c shared_preload_libraries=pg_stat_statements,auto_explain
            -c auto_explain.log_min_duration=5 -c auto_explain.log_analyze=on
            -c auto_explain.log_buffers=on -c auto_explain.sample_rate=0.05)
else
  PG_EXTRA=(-c shared_preload_libraries=pg_stat_statements)
fi
mkdir -p "$OUT"

cleanup() {
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null || true
  [ -n "${SCRAPER_PID:-}" ] && kill "$SCRAPER_PID" 2>/dev/null || true
  docker rm -f "$PG_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "== PostgreSQL ($PG_NAME)"
docker run -d --name "$PG_NAME" -p "$PG_PORT:5432" \
  -e POSTGRES_DB=ecopay -e POSTGRES_USER=ecopay -e POSTGRES_PASSWORD=ecopay \
  postgres:16-alpine "${PG_EXTRA[@]}" -c max_connections=200 >/dev/null
for _ in $(seq 1 60); do docker exec "$PG_NAME" pg_isready -U ecopay >/dev/null 2>&1 && break; sleep 1; done
sleep 2
docker exec "$PG_NAME" psql -U ecopay -d ecopay -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements" >/dev/null

echo "== Build"
(cd "$ROOT" && ./mvnw -B -q -o -DskipTests package)
JAR="$(ls "$ROOT"/target/ecopay-backend-*.jar | grep -v plain | head -1)"

echo "== Start backend on :$PORT (mock provider)"
# Throwaway per-run keys: nothing reusable is stored in the repository.
PERF_JWT_SECRET="$(head -c 48 /dev/urandom | base64 | tr -d '\n')"
PERF_FIELD_KEY="$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
SERVER_PORT="$PORT" \
POSTGRES_HOST=localhost POSTGRES_PORT="$PG_PORT" POSTGRES_DB=ecopay POSTGRES_USER=ecopay POSTGRES_PASSWORD=ecopay \
JWT_SECRET="$PERF_JWT_SECRET" \
APP_SECURITY_FIELD_ENCRYPTION_KEY="$PERF_FIELD_KEY" \
PAYMENTS_PROVIDER=mock SMS_PROVIDER=logging APP_DEV_AUTO_VERIFY_EMAIL=true \
APP_EMAIL_MX_CHECK_ENABLED=false APP_EMAIL_STARTUP_CHECK_ENABLED=false \
MAIL_HOST=localhost MAIL_PORT=1025 MAIL_USERNAME=perf@test.kz MAIL_PASSWORD=x \
APP_PRICING_ENABLED=false APP_RATE_LIMIT_STORE=jdbc \
APP_PHONE_DEV_BYPASS_CODE=000000 \
APP_RATE_LIMIT_ROOM_JOIN_MAX=1000 \
JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:--Xms512m -Xmx1g}" \
  java -jar "$JAR" > "$OUT/app.log" 2>&1 &
APP_PID=$!
for _ in $(seq 1 120); do
  curl -fsS "http://localhost:$PORT/actuator/health/readiness" >/dev/null 2>&1 && break
  sleep 2
done
curl -fsS "http://localhost:$PORT/actuator/health/readiness" >/dev/null

echo "== Scraping /actuator/prometheus every 10s"
(
  while true; do
    echo "# ts=$(date +%s)" >> "$OUT/prometheus.txt"
    curl -fsS "http://localhost:$PORT/actuator/prometheus" 2>/dev/null \
      | grep -E '^(hikaricp_connections(_active|_idle|_pending|_max)?|hikaricp_connections_acquire_seconds_(sum|count|max)|hikaricp_connections_timeout_total|jvm_memory_used_bytes\{area="heap"|jvm_gc_pause_seconds_(sum|count|max)|process_cpu_usage|system_cpu_usage|jvm_threads_live_threads|tomcat_threads_busy_threads|executor_active_threads|ecopay_)' \
      >> "$OUT/prometheus.txt" || true
    sleep 10
  done
) &
SCRAPER_PID=$!

echo "== k6 ($PROFILE)"
# Git Bash on Windows rewrites container paths; disable that and mount native host paths.
HOST_K6="$( (cd "$ROOT/perf/k6" && pwd -W) 2>/dev/null || echo "$ROOT/perf/k6")"
HOST_OUT="$( (cd "$OUT" && pwd -W) 2>/dev/null || echo "$OUT")"
MSYS_NO_PATHCONV=1 docker run --rm -i --add-host=host.docker.internal:host-gateway \
  -e PROFILE="$PROFILE" -e BASE_URL="http://host.docker.internal:$PORT" \
  -e ECOPAY_PROVIDER_IS_MOCK=yes -e RUN_ID="$STAMP" \
  -v "$HOST_K6:/scripts" -v "$HOST_OUT:/out" \
  grafana/k6:latest run --summary-export=/out/k6-summary.json /scripts/ecopay-mixed.js \
  2>&1 | tee "$OUT/k6.txt" || true

echo "== Slowest statements"
docker exec "$PG_NAME" psql -U ecopay -d ecopay -c \
  "SELECT calls, round(mean_exec_time::numeric,2) AS mean_ms, round(max_exec_time::numeric,2) AS max_ms,
          round(total_exec_time::numeric,0) AS total_ms, left(regexp_replace(query, '\s+', ' ', 'g'), 160) AS query
     FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname='ecopay')
     ORDER BY total_exec_time DESC LIMIT 20" > "$OUT/slow-queries.txt"
cat "$OUT/slow-queries.txt"

if [ "$EXPLAIN" = "1" ]; then
  echo "== EXPLAIN ANALYZE at volume ($EXPLAIN_ROWS rows per big table)"
  docker exec -i "$PG_NAME" psql -U ecopay -d ecopay -v rows="$EXPLAIN_ROWS"     < "$ROOT/perf/explain.sql" > "$OUT/explain.txt" 2>&1 || true
  docker logs "$PG_NAME" 2>&1 | grep -A40 "duration:" > "$OUT/auto-explain.log" || true
  echo "Plans: $OUT/explain.txt, $OUT/auto-explain.log"
fi
echo "Results: $OUT"
