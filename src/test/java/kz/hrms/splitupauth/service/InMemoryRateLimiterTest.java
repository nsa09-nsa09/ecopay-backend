package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import kz.hrms.splitupauth.exception.TooManyRequestsException;
import org.junit.jupiter.api.Test;

/** Block 4d: the limiter's key store is bounded (Caffeine), not an unbounded ConcurrentHashMap. */
class InMemoryRateLimiterTest {

  @Test
  void keyStoreStaysBounded_underManyDistinctKeys() {
    InMemoryRateLimiter limiter = new InMemoryRateLimiter(100, 86400);

    // 10_000 distinct one-shot keys — the old ConcurrentHashMap would retain every one forever.
    for (int i = 0; i < 10_000; i++) {
      limiter.check("visit:ip:" + i, 1000, 60, "msg");
    }

    long tracked = limiter.trackedKeyCount();
    assertTrue(
        tracked <= 150,
        "expected the bounded cache to cap retained keys near maximumSize=100, got " + tracked);
  }

  @Test
  void stillEnforcesTheSlidingWindow() {
    InMemoryRateLimiter limiter = new InMemoryRateLimiter(1000, 86400);
    assertDoesNotThrow(() -> limiter.check("k", 2, 60, "msg"));
    assertDoesNotThrow(() -> limiter.check("k", 2, 60, "msg"));
    assertThrows(TooManyRequestsException.class, () -> limiter.check("k", 2, 60, "msg"));
  }
}
