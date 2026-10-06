package kz.hrms.splitupauth.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import kz.hrms.splitupauth.exception.TooManyRequestsException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Lightweight in-process sliding-window rate limiter. Per-instance only: with several replicas each
 * one enforces its own budget, so production uses {@link JdbcRateLimiter} instead (the prod startup
 * guard rejects this store). The key map is bounded: idle keys are swept periodically and, if the
 * map still exceeds {@code app.rate-limit.memory.max-keys}, the oldest keys are evicted so an
 * attacker cycling identifiers cannot grow the heap without bound.
 */
@Component
@ConditionalOnProperty(name = "app.rate-limit.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryRateLimiter implements RateLimiter {

  private static final long SWEEP_EVERY_HITS = 1_024;

  /** Longest window any caller uses (24h) — keys idle for longer can never block again. */
  private static final long MAX_WINDOW_MS = 24L * 60 * 60 * 1000;

  private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
  private final AtomicLong hitCounter = new AtomicLong();
  private final int maxKeys;

  @Autowired
  public InMemoryRateLimiter(@Value("${app.rate-limit.memory.max-keys:100000}") int maxKeys) {
    this.maxKeys = Math.max(1_000, maxKeys);
  }

  public InMemoryRateLimiter() {
    this(100_000);
  }

  @Override
  public void check(String key, int maxOps, long windowSeconds, String message) {
    long now = System.currentTimeMillis();
    long windowMs = windowSeconds * 1000L;
    Deque<Long> dq = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
    synchronized (dq) {
      while (!dq.isEmpty() && now - dq.peekFirst() > windowMs) {
        dq.pollFirst();
      }
      if (dq.size() >= maxOps) {
        throw new TooManyRequestsException(message);
      }
      dq.addLast(now);
    }
    if (hitCounter.incrementAndGet() % SWEEP_EVERY_HITS == 0 || hits.size() > maxKeys) {
      sweep(now);
    }
  }

  int trackedKeys() {
    return hits.size();
  }

  private void sweep(long now) {
    Iterator<Map.Entry<String, Deque<Long>>> it = hits.entrySet().iterator();
    while (it.hasNext()) {
      Deque<Long> dq = it.next().getValue();
      synchronized (dq) {
        Long last = dq.peekLast();
        if (last == null || now - last > MAX_WINDOW_MS) {
          it.remove();
        }
      }
    }
    if (hits.size() <= maxKeys) {
      return;
    }
    // Still over budget with live keys: drop the least recently used ones. Losing a bucket only
    // makes the limiter briefly more permissive for that key, never stricter.
    long excess = hits.size() - (long) maxKeys;
    hits.entrySet().stream()
        .sorted(
            (a, b) -> {
              Long la = a.getValue().peekLast();
              Long lb = b.getValue().peekLast();
              return Long.compare(la == null ? 0 : la, lb == null ? 0 : lb);
            })
        .limit(excess)
        .map(Map.Entry::getKey)
        .toList()
        .forEach(hits::remove);
  }
}
