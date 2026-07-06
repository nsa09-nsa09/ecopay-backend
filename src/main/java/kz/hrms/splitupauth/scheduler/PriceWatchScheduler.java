package kz.hrms.splitupauth.scheduler;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import kz.hrms.splitupauth.entity.PriceWatchProvider;
import kz.hrms.splitupauth.pricing.PriceWatchService;
import kz.hrms.splitupauth.repository.PriceWatchProviderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Price Watch scheduler — the Price Watch analogue of {@link FxRateScheduler}. Every
 * {@code app.pricing.tick-minutes} it selects providers whose {@code nextCheckAt} has come due
 * and dispatches each one to a bounded {@link ThreadPoolTaskExecutor}, with a per-domain
 * semaphore preventing multiple concurrent hits on the same host.
 *
 * <p>Startup refresh is off by default so a boot never accidentally scrapes third-party sites;
 * flip {@code app.pricing.startup-refresh=true} in dev if you want the queue drained on start.
 */
@Slf4j
@Component
public class PriceWatchScheduler {

  private final PriceWatchProviderRepository providerRepository;
  private final PriceWatchService priceWatchService;
  private final ThreadPoolTaskExecutor executor;
  private final int maxConcurrency;
  private final int perDomainConcurrency;
  private final boolean enabled;
  private final boolean startupRefresh;

  /** Per-host semaphore so we never fire N parallel requests at the same site. */
  private final Map<String, Semaphore> perDomainLimit = new ConcurrentHashMap<>();

  public PriceWatchScheduler(
      PriceWatchProviderRepository providerRepository,
      PriceWatchService priceWatchService,
      @Value("${app.pricing.max-concurrency:3}") int maxConcurrency,
      @Value("${app.pricing.per-domain-concurrency:1}") int perDomainConcurrency,
      @Value("${app.pricing.enabled:true}") boolean enabled,
      @Value("${app.pricing.startup-refresh:false}") boolean startupRefresh) {
    this.providerRepository = providerRepository;
    this.priceWatchService = priceWatchService;
    this.maxConcurrency = Math.max(1, maxConcurrency);
    this.perDomainConcurrency = Math.max(1, perDomainConcurrency);
    this.enabled = enabled;
    this.startupRefresh = startupRefresh;
    this.executor = buildExecutor(this.maxConcurrency);
  }

  private static ThreadPoolTaskExecutor buildExecutor(int size) {
    ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
    ex.setCorePoolSize(size);
    ex.setMaxPoolSize(size);
    ex.setQueueCapacity(64);
    ex.setThreadNamePrefix("price-watch-");
    ex.setWaitForTasksToCompleteOnShutdown(true);
    ex.setAwaitTerminationSeconds(10);
    ex.initialize();
    return ex;
  }

  /** Async so a stalled upstream cannot delay startup. */
  @Async
  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    if (!enabled || !startupRefresh) {
      log.debug("PriceWatch startup refresh disabled (enabled={}, startupRefresh={})",
          enabled, startupRefresh);
      return;
    }
    log.info("PriceWatch startup: draining due queue");
    tick();
  }

  /**
   * The scheduler tick: pull the due batch and dispatch each entry. The SpEL expression turns
   * {@code tick-minutes} into a millisecond fixedRate value.
   */
  @Scheduled(
      fixedRateString = "#{${app.pricing.tick-minutes:5} * 60 * 1000}",
      initialDelayString = "#{${app.pricing.tick-minutes:5} * 60 * 1000}")
  public void tick() {
    if (!enabled) {
      return;
    }
    List<PriceWatchProvider> due = providerRepository.findDueForCheck(LocalDateTime.now());
    if (due.isEmpty()) {
      return;
    }
    log.info("PriceWatch tick: {} provider(s) due", due.size());
    // Group by domain so we can wall-clock stagger identical-host entries. The
    // per-host semaphore handles the hard limit; the delay just spreads the load.
    Map<String, Integer> perHostSubmitted = new HashMap<>();
    for (PriceWatchProvider provider : due) {
      String host = safeHost(provider.getUrl());
      int within = perHostSubmitted.merge(host, 1, Integer::sum);
      long jitterMs = 200L + ThreadLocalRandom.current().nextLong(600);
      long stagger = (within - 1) * 750L;
      executor.execute(() -> runOne(provider, host, jitterMs + stagger));
    }
  }

  private void runOne(PriceWatchProvider provider, String host, long delayMs) {
    Semaphore limiter = perDomainLimit.computeIfAbsent(host,
        h -> new Semaphore(perDomainConcurrency));
    try {
      if (delayMs > 0) Thread.sleep(delayMs);
      limiter.acquire();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return;
    }
    try {
      priceWatchService.checkProvider(provider.getId());
    } catch (Exception ex) {
      log.warn("PriceWatch tick failed for provider {}: {}",
          provider.getId(), ex.getMessage());
    } finally {
      limiter.release();
    }
  }

  private static String safeHost(String url) {
    if (url == null) return "unknown";
    try {
      String h = URI.create(url).getHost();
      return h == null ? "unknown" : h.toLowerCase();
    } catch (Exception ex) {
      return "unknown";
    }
  }

  /** Exposed for admin diagnostics; also lets tests drive the pool. */
  public TaskExecutor executor() {
    return executor;
  }

  /** Package-visible helper used by tests to trace scheduling decisions. */
  List<String> dueUrls() {
    List<PriceWatchProvider> due = providerRepository.findDueForCheck(LocalDateTime.now());
    List<String> out = new ArrayList<>();
    for (PriceWatchProvider p : due) out.add(p.getUrl());
    return out;
  }
}
