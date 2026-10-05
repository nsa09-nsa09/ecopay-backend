package kz.hrms.splitupauth.scheduler;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL advisory-lock wrapper so each @Scheduled job runs on exactly ONE node when the backend
 * runs with multiple replicas (there is no ShedLock here). Each job has a distinct {@link Key}.
 *
 * <p>Implementation: a <b>session-level</b> advisory lock ({@code pg_try_advisory_lock}) acquired on
 * a dedicated connection that is held for the job's duration and released in a finally block
 * ({@code pg_advisory_unlock}). The job itself still runs with its own transactions on other pooled
 * connections, so this never changes the job's transaction semantics. If the lock can't be acquired,
 * another node already holds it and this node skips the run.
 *
 * <p>This is a single-runner optimisation, not a money-safety mechanism: every money operation must
 * remain independently idempotent (idempotency keys, terminal-status guards), so correctness does
 * not depend on the lock — the lock only prevents wasted/contending duplicate work across nodes.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SchedulerLock {

  /** One stable, distinct advisory-lock key per scheduled job. Never reuse a value. */
  public enum Key {
    ACCESS_CONFIRMATION(100_001L),
    ACCOUNT_RESTRICTION(100_002L),
    CLEANUP_EXPIRED_TOKENS(100_003L),
    CLEANUP_OLD_LOGIN_ATTEMPTS(100_004L),
    CLEANUP_STAFF_2FA(100_005L),
    EXPIRE_STALE_PAYMENT_INTENTS(100_006L),
    FREEDOM_WEBHOOK_RETRY(100_007L),
    FX_REFRESH_INTRADAY(100_008L),
    FX_REFRESH_DAILY(100_009L),
    PENDING_MEMBERSHIP_ESCALATION(100_010L),
    PRICE_WATCH(100_011L),
    ROOM_VERIFICATION(100_012L),
    PAYOUT_DISPATCH(100_013L),
    REFUND_DISPATCH(100_014L),
    RECURRING_CHARGES(100_015L),
    /** Reserved for the single-runner integration test; never used by a real job. */
    TEST_ONLY(999_999_999L);

    private final long value;

    Key(long value) {
      this.value = value;
    }
  }

  private final DataSource dataSource;

  /** Runs {@code job} iff this node wins the advisory lock for {@code key}; otherwise skips. */
  public void runExclusive(Key key, Runnable job) {
    try (Connection conn = dataSource.getConnection()) {
      if (!tryLock(conn, key.value)) {
        log.debug("Scheduler lock {} held by another node; skipping this run", key);
        return;
      }
      try {
        job.run();
      } finally {
        unlock(conn, key.value);
      }
    } catch (Exception e) {
      // Never let a lock/transport failure escape a scheduler tick.
      log.warn("Scheduler lock {} run failed: {}", key, e.getMessage());
    }
  }

  private boolean tryLock(Connection conn, long key) throws Exception {
    try (PreparedStatement ps = conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
      ps.setLong(1, key);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() && rs.getBoolean(1);
      }
    }
  }

  private void unlock(Connection conn, long key) {
    try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
      ps.setLong(1, key);
      ps.execute();
    } catch (Exception e) {
      log.warn("Failed to release scheduler advisory lock {}: {}", key, e.getMessage());
    }
  }
}
