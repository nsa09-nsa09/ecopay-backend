package kz.hrms.splitupauth.scheduler;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Cluster-wide "run on one replica at a time" guard for {@code @Scheduled} jobs.
 *
 * <p>Backed by the {@code scheduler_locks} table: acquiring is one atomic upsert that only succeeds
 * when the previous lease has expired, so it works on PostgreSQL and CockroachDB alike (CockroachDB
 * has no real advisory locks) and survives crashes: a dead holder's lease simply runs out after
 * {@code lockAtMostFor}. The lock only prevents duplicate WORK; every money-moving job keeps its
 * own row-level leases and idempotency keys, so correctness never depends on this lock alone.
 *
 * <p>Each run also records bounded-cardinality metrics: {@code ecopay.scheduler.run} (timer tagged
 * by job and outcome) and {@code ecopay.scheduler.last.success} (epoch seconds), from which
 * scheduler lag is {@code time() - last_success}.
 */
@Component
@Slf4j
public class SchedulerLock {

  private static final String ACQUIRE =
      "INSERT INTO scheduler_locks (name, locked_until, locked_at, locked_by) VALUES (?, ?, ?, ?) "
          + "ON CONFLICT (name) DO UPDATE SET locked_until = EXCLUDED.locked_until, "
          + "locked_at = EXCLUDED.locked_at, locked_by = EXCLUDED.locked_by "
          + "WHERE scheduler_locks.locked_until <= ? "
          + "RETURNING name";

  private static final String RELEASE =
      "UPDATE scheduler_locks SET locked_until = ? WHERE name = ? AND locked_by = ?";

  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;
  private final ObjectProvider<MeterRegistry> meterRegistry;
  private final String instanceId;
  private final Map<String, AtomicLong> lastSuccess = new ConcurrentHashMap<>();

  public SchedulerLock(
      JdbcTemplate jdbcTemplate, Clock clock, ObjectProvider<MeterRegistry> meterRegistry) {
    this.jdbcTemplate = jdbcTemplate;
    this.clock = clock;
    this.meterRegistry = meterRegistry;
    this.instanceId = ManagementFactory.getRuntimeMXBean().getName() + "/" + UUID.randomUUID();
  }

  /**
   * Runs {@code task} if no other replica holds {@code name}. Returns false when skipped.
   *
   * @param lockAtMostFor upper bound for a run; must exceed the job's worst-case duration so a slow
   *     but alive run is not overlapped. A crashed holder blocks the job for at most this long.
   */
  public boolean runExclusive(String name, Duration lockAtMostFor, Runnable task) {
    LocalDateTime now = LocalDateTime.now(clock);
    boolean acquired;
    try {
      acquired =
          !jdbcTemplate
              .queryForList(
                  ACQUIRE, String.class, name, now.plus(lockAtMostFor), now, instanceId, now)
              .isEmpty();
    } catch (RuntimeException ex) {
      log.warn("Scheduler lock {} unavailable, skipping run: {}", name, ex.toString());
      return false;
    }
    if (!acquired) {
      return false;
    }
    long started = System.nanoTime();
    String outcome = "success";
    try {
      task.run();
      recordSuccess(name);
      return true;
    } catch (RuntimeException ex) {
      outcome = "error";
      throw ex;
    } finally {
      recordDuration(name, outcome, System.nanoTime() - started);
      try {
        jdbcTemplate.update(RELEASE, LocalDateTime.now(clock), name, instanceId);
      } catch (RuntimeException ex) {
        // Lease expires on its own; the next run is delayed by at most lockAtMostFor.
        log.warn("Scheduler lock {} release failed: {}", name, ex.toString());
      }
    }
  }

  private void recordSuccess(String job) {
    AtomicLong holder =
        lastSuccess.computeIfAbsent(
            job,
            key -> {
              AtomicLong value = new AtomicLong();
              MeterRegistry registry = meterRegistry.getIfAvailable();
              if (registry != null) {
                registry.gauge(
                    "ecopay.scheduler.last.success",
                    java.util.List.of(io.micrometer.core.instrument.Tag.of("job", key)),
                    value);
              }
              return value;
            });
    holder.set(clock.millis() / 1000L);
  }

  private void recordDuration(String job, String outcome, long nanos) {
    MeterRegistry registry = meterRegistry.getIfAvailable();
    if (registry == null) {
      return;
    }
    Timer.builder("ecopay.scheduler.run")
        .tag("job", job)
        .tag("outcome", outcome)
        .register(registry)
        .record(Duration.ofNanos(nanos));
  }
}
