package kz.hrms.splitupauth.scheduler;

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

  @Scheduled(fixedDelayString = "${app.webhooks.freedom.retry-delay-ms:60000}")
  @Transactional
  public void retryDueWebhooks() {
    try {
      coordinator.retryDueWebhooks();
    } catch (RuntimeException ex) {
      log.error("Freedom webhook retry scan failed: {}", ex.toString(), ex);
    }
  }
}
