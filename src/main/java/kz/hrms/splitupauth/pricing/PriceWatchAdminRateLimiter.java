package kz.hrms.splitupauth.pricing;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import kz.hrms.splitupauth.exception.TooManyRequestsException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PriceWatchAdminRateLimiter {

  private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

  @Value("${app.pricing.admin-rate-limit.max:20}")
  private int max;

  @Value("${app.pricing.admin-rate-limit.window-seconds:60}")
  private long windowSeconds;

  public void check(String actor, String action) {
    String key = (actor == null || actor.isBlank() ? "unknown" : actor) + ":" + action;
    long now = Instant.now().getEpochSecond();
    Deque<Long> queue = hits.computeIfAbsent(key, ignored -> new ArrayDeque<>());
    synchronized (queue) {
      while (!queue.isEmpty() && queue.peekFirst() <= now - windowSeconds) {
        queue.removeFirst();
      }
      if (queue.size() >= max) {
        throw new TooManyRequestsException("Price Watch admin action is rate-limited");
      }
      queue.addLast(now);
    }
  }
}
