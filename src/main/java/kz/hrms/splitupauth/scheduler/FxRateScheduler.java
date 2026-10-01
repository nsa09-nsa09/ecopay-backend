package kz.hrms.splitupauth.scheduler;

import kz.hrms.splitupauth.service.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Refreshes the FX → KZT snapshot from upstream on three triggers:
 *
 * <ol>
 *   <li><b>Startup</b> — async on {@link ApplicationReadyEvent} so the first HTTP request after a
 *       deploy already sees live rates instead of the hand-curated fallback table baked into {@link
 *       ExchangeRateService}.
 *   <li><b>Intraday</b> — every {@code app.fx.refresh-interval-hours} hours (default 6). Keeps
 *       rates fresh for users without flooding the free upstream API.
 *   <li><b>Daily</b> — fixed 04:15 Almaty cron, kept so the snapshot is refreshed even if a deploy
 *       happened to land just after the last intraday tick.
 * </ol>
 *
 * Cron uses the Spring scheduler thread's default TZ (Asia/Almaty in production thanks to
 * SplitUpAuthApplication.main).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FxRateScheduler {

  private final ExchangeRateService exchangeRateService;

  /** Async so a slow upstream cannot delay the rest of application startup. */
  @Async
  @EventListener(ApplicationReadyEvent.class)
  public void refreshOnStartup() {
    log.info("FX startup refresh: requesting fresh rates from upstream");
    exchangeRateService.refresh();
  }

  /**
   * Intraday refresh. SpEL converts the property's hour value into the millisecond rate Spring's
   * scheduler needs. Initial delay equals the rate so we don't double-fire alongside the startup
   * listener.
   */
  @Scheduled(
      fixedRateString = "#{${app.fx.refresh-interval-hours:6} * 60 * 60 * 1000}",
      initialDelayString = "#{${app.fx.refresh-interval-hours:6} * 60 * 60 * 1000}")
  public void refreshIntraday() {
    exchangeRateService.refresh();
  }

  @Scheduled(cron = "0 15 4 * * ?")
  public void refreshDaily() {
    exchangeRateService.refresh();
  }
}
