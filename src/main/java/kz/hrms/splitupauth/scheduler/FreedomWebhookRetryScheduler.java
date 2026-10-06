package kz.hrms.splitupauth.scheduler;

import java.time.Duration;
import kz.hrms.splitupauth.service.FreedomWebhookInboxCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class FreedomWebhookRetryScheduler {

  private final FreedomWebhookInboxCoordinator coordinator;
  private final SchedulerLock schedulerLock;

  @Scheduled(fixedDelayString = "${app.webhooks.freedom.retry-delay-ms:60000}")
  public void retryDueWebhooks() {
    try {
      // Each inbox row is additionally claimed with its own lease, so the lock only avoids two
      // replicas scanning the same batch.
      schedulerLock.runExclusive(
          "freedom-webhook-retry", Duration.ofMinutes(10), coordinator::retryDueWebhooks);
    } catch (RuntimeException ex) {
      log.error("Freedom webhook retry scan failed: {}", ex.toString(), ex);
    }
  }
}
