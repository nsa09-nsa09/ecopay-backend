package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.exception.TooManyLoginAttemptsException;
import kz.hrms.splitupauth.exception.TooManyRequestsException;
import kz.hrms.splitupauth.scheduler.SchedulerLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Multi-replica behaviour against real PostgreSQL: two {@link SchedulerLock}/{@link
 * JdbcRateLimiter} instances stand in for two application nodes sharing one database.
 */
class PlatformConcurrencyIntegrationTest extends AbstractIntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private Clock clock;
  @Autowired private SiteVisitService siteVisitService;
  @Autowired private RateLimitService rateLimitService;

  @AfterEach
  void clearRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  private SchedulerLock node() {
    return new SchedulerLock(
        jdbcTemplate,
        clock,
        new DefaultListableBeanFactory()
            .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
  }

  @Test
  void schedulerRunningOnTwoNodesExecutesTheJobOnce() throws Exception {
    String job = "it-job-" + UUID.randomUUID();
    SchedulerLock nodeA = node();
    SchedulerLock nodeB = node();
    CountDownLatch insideA = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger runs = new AtomicInteger();

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> a =
          pool.submit(
              () ->
                  nodeA.runExclusive(
                      job,
                      Duration.ofMinutes(5),
                      () -> {
                        runs.incrementAndGet();
                        insideA.countDown();
                        try {
                          release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                      }));
      assertTrue(insideA.await(10, TimeUnit.SECONDS));
      boolean bRan = nodeB.runExclusive(job, Duration.ofMinutes(5), runs::incrementAndGet);
      release.countDown();

      assertFalse(bRan, "second node must skip while the first holds the lease");
      assertTrue(a.get(10, TimeUnit.SECONDS));
      assertEquals(1, runs.get());
      // Released: the next tick on any node runs again.
      assertTrue(nodeB.runExclusive(job, Duration.ofMinutes(5), runs::incrementAndGet));
      assertEquals(2, runs.get());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void crashedHolderBlocksOnlyUntilItsLeaseExpires() {
    String job = "it-crash-" + UUID.randomUUID();
    LocalDateTime now = LocalDateTime.now(clock);
    jdbcTemplate.update(
        "INSERT INTO scheduler_locks (name, locked_until, locked_at, locked_by) VALUES (?, ?, ?, ?)",
        job,
        now.plusMinutes(10),
        now,
        "dead-node");
    assertFalse(node().runExclusive(job, Duration.ofMinutes(1), () -> {}));

    jdbcTemplate.update(
        "UPDATE scheduler_locks SET locked_until = ? WHERE name = ?", now.minusSeconds(1), job);
    assertTrue(node().runExclusive(job, Duration.ofMinutes(1), () -> {}));
  }

  @Test
  void jdbcRateLimiterBudgetIsSharedAcrossNodes() {
    JdbcRateLimiter nodeA = new JdbcRateLimiter(jdbcTemplate, clock);
    JdbcRateLimiter nodeB = new JdbcRateLimiter(jdbcTemplate, clock);
    String key = "it-room-join:" + UUID.randomUUID();

    nodeA.check(key, 3, 600, "limited");
    nodeB.check(key, 3, 600, "limited");
    nodeA.check(key, 3, 600, "limited");
    assertThrows(TooManyRequestsException.class, () -> nodeB.check(key, 3, 600, "limited"));

    Integer rawKeys =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM rate_limit_counters WHERE bucket_key LIKE 'it-room-join%'",
            Integer.class);
    assertEquals(0, rawKeys, "keys must be stored hashed, never raw identifiers");
  }

  @Test
  void concurrentVisitPingsAreCountedAtomicallyInOneRow() throws Exception {
    UUID visitor = UUID.randomUUID();
    int pings = 12;
    ExecutorService pool = Executors.newFixedThreadPool(6);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < pings; i++) {
        futures.add(
            pool.submit(
                () -> {
                  MockHttpServletRequest request = new MockHttpServletRequest();
                  request.setRemoteAddr("192.0.2." + (int) (Math.random() * 200));
                  request.setCookies(new jakarta.servlet.http.Cookie("vid", visitor.toString()));
                  siteVisitService.recordVisit(
                      request,
                      new MockHttpServletResponse(),
                      "/rooms/5?resetToken=secret#frag",
                      null);
                  return null;
                }));
      }
      for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    Integer rows =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM site_visit WHERE visitor_id = ?", Integer.class, visitor);
    Integer pageCount =
        jdbcTemplate.queryForObject(
            "SELECT page_count FROM site_visit WHERE visitor_id = ?", Integer.class, visitor);
    String path =
        jdbcTemplate.queryForObject(
            "SELECT last_path FROM site_visit WHERE visitor_id = ?", String.class, visitor);
    assertEquals(1, rows);
    assertEquals(pings, pageCount);
    assertEquals("/rooms/5", path, "query strings and fragments must never be stored");
  }

  @Test
  void loginThrottleHasAnIpBucketThatSpansAccounts() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("198.51.100." + (int) (Math.random() * 200 + 1));
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

    // 30 failures spread over many different accounts from one address (credential spraying).
    for (int i = 0; i < 30; i++) {
      rateLimitService.recordLoginAttempt("spray-" + i + "-" + UUID.randomUUID() + "@x.kz", false);
    }

    assertThrows(
        TooManyLoginAttemptsException.class,
        () -> rateLimitService.checkLoginAttempts("fresh-" + UUID.randomUUID() + "@x.kz"));
    Integer withIp =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM login_attempts WHERE ip_address = ?",
            Integer.class,
            request.getRemoteAddr());
    assertEquals(30, withIp);
  }

  @Test
  void loginAccountBucketStillAppliesWithoutARequest() {
    // Spring's test listener binds a mock request by default; model a scheduler/internal caller.
    RequestContextHolder.resetRequestAttributes();
    String email = "acct-" + UUID.randomUUID() + "@x.kz";
    for (int i = 0; i < 5; i++) {
      rateLimitService.recordLoginAttempt(email, false);
    }
    assertThrows(
        TooManyLoginAttemptsException.class, () -> rateLimitService.checkLoginAttempts(email));
    assertNull(
        jdbcTemplate.queryForObject(
            "SELECT MAX(ip_address) FROM login_attempts WHERE email = ?", String.class, email));
  }
}
