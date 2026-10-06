package kz.hrms.splitupauth.service;

/**
 * Sliding-window abuse limiter for actions such as room create/join, SMS sends and feedback.
 *
 * <p>Two implementations exist: {@link InMemoryRateLimiter} (per JVM, dev/test and single-instance
 * deployments) and {@link JdbcRateLimiter} (shared through the primary database, safe with several
 * application replicas). The store is selected with {@code app.rate-limit.store}.
 */
public interface RateLimiter {

  /**
   * Records one hit for {@code key} and throws {@link
   * kz.hrms.splitupauth.exception.TooManyRequestsException} if more than {@code maxOps} hits
   * occurred within the last {@code windowSeconds}.
   */
  void check(String key, int maxOps, long windowSeconds, String message);
}
