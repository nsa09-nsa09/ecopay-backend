package kz.hrms.splitupauth.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import kz.hrms.splitupauth.exception.TooManyRequestsException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Lightweight in-process sliding-window rate limiter for abuse-prone actions (room create / join,
 * registration, password-reset, site-visit pings). Per-instance only — for a multi-instance
 * deployment production uses {@link JdbcRateLimiter} ({@code app.rate-limit.store=jdbc}; the prod
 * startup guard rejects the memory store). Keys are typically "action:scope:id".
 *
 * <p>Backed by a <b>bounded</b> Caffeine cache with {@code expireAfterAccess}, so one-shot keys
 * (e.g. {@code visit:ip:<ip>} for a visitor seen once) are evicted instead of accumulating forever
 * — the previous {@link java.util.concurrent.ConcurrentHashMap} grew without bound. {@code
 * expireAfterAccess} is sized to cover the longest window in use so an active key is never evicted
 * mid-window; {@code maximumSize} caps worst-case memory. The {@link #check} signature is unchanged
 * so no call site needs to change.
 */
@Component
@ConditionalOnProperty(name = "app.rate-limit.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryRateLimiter implements RateLimiter {

  private final Cache<String, Deque<Long>> hits;

  public InMemoryRateLimiter(
      @Value("${app.rate-limit.in-memory.max-size:200000}") long maxSize,
      @Value("${app.rate-limit.in-memory.expire-after-access-seconds:86400}")
          long expireAfterAccessSeconds) {
    this.hits =
        Caffeine.newBuilder()
            .maximumSize(maxSize)
            .expireAfterAccess(Duration.ofSeconds(expireAfterAccessSeconds))
            .build();
  }

  /**
   * Records one hit for {@code key} and throws {@link TooManyRequestsException} if more than {@code
   * maxOps} hits occurred within the last {@code windowSeconds}.
   */
  @Override
  public void check(String key, int maxOps, long windowSeconds, String message) {
    long now = System.currentTimeMillis();
    long windowMs = windowSeconds * 1000L;
    Deque<Long> dq = hits.get(key, k -> new ArrayDeque<>());
    synchronized (dq) {
      while (!dq.isEmpty() && now - dq.peekFirst() > windowMs) {
        dq.pollFirst();
      }
      if (dq.size() >= maxOps) {
        throw new TooManyRequestsException(message);
      }
      dq.addLast(now);
    }
  }

  /**
   * Number of distinct keys currently tracked, after forcing pending evictions. Test/observability
   * hook — Caffeine eviction is asynchronous, so this calls {@code cleanUp()} first.
   */
  public long trackedKeyCount() {
    hits.cleanUp();
    return hits.estimatedSize();
  }
}
