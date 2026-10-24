package kz.hrms.splitupauth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import kz.hrms.splitupauth.exception.TooManyRequestsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Database-backed sliding-window limiter shared by every application replica.
 *
 * <p>Uses the "sliding window counter" approximation: one row per (key, fixed window) incremented
 * with a single atomic upsert, plus a weighted share of the previous window. This needs no extra
 * infrastructure (the primary PostgreSQL/CockroachDB is already a hard dependency) and costs one
 * upsert + one primary-key read per check. Keys are stored as SHA-256 digests, so emails, phone
 * numbers and IP addresses never land in the counter table.
 */
@Component
@ConditionalOnProperty(name = "app.rate-limit.store", havingValue = "jdbc")
@Slf4j
public class JdbcRateLimiter implements RateLimiter {

  private static final String UPSERT =
      "INSERT INTO rate_limit_counters (bucket_key, window_start, hits, expires_at) "
          + "VALUES (?, ?, 1, ?) "
          + "ON CONFLICT (bucket_key, window_start) "
          + "DO UPDATE SET hits = rate_limit_counters.hits + 1 "
          + "RETURNING hits";

  private static final String READ_PREVIOUS =
      "SELECT hits FROM rate_limit_counters WHERE bucket_key = ? AND window_start = ?";

  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  public JdbcRateLimiter(JdbcTemplate jdbcTemplate, Clock clock) {
    this.jdbcTemplate = jdbcTemplate;
    this.clock = clock;
  }

  @Override
  public void check(String key, int maxOps, long windowSeconds, String message) {
    long window = Math.max(1, windowSeconds);
    long nowSeconds = clock.millis() / 1000L;
    long windowStart = nowSeconds - Math.floorMod(nowSeconds, window);
    String bucket = digest(window + "|" + key);

    Long current =
        jdbcTemplate.queryForObject(
            UPSERT, Long.class, bucket, windowStart, windowStart + 2 * window);
    List<Long> previous =
        jdbcTemplate.queryForList(READ_PREVIOUS, Long.class, bucket, windowStart - window);

    double elapsedFraction = (double) (nowSeconds - windowStart) / window;
    double previousWeight = previous.isEmpty() ? 0 : previous.get(0) * (1.0 - elapsedFraction);
    double estimate = previousWeight + (current == null ? 1 : current);
    if (estimate > maxOps) {
      throw new TooManyRequestsException(message);
    }
  }

  /** Removes windows that can no longer influence any decision. */
  public int purgeExpired() {
    return jdbcTemplate.update(
        "DELETE FROM rate_limit_counters WHERE expires_at < ?", clock.millis() / 1000L);
  }

  private static String digest(String value) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
