package kz.hrms.splitupauth.scheduler;

import java.time.Duration;
import kz.hrms.splitupauth.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Settles payment intents whose provider outcome is still open — a lost result callback, an
 * ambiguous initiation (timeout/unsigned answer) or an accepted-but-unconfirmed recurring charge —
 * by asking FreedomPay for the payment state. Gentle by design: small batches, calls spaced by
 * {@code app.payments.reconciliation.spacing-ms} (FreedomPay recommends 1.5-2 s between payment API
 * calls), each intent re-queried at most every 5 minutes and capped in attempts.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentReconciliationScheduler {

  private final PaymentService paymentService;
  private final SchedulerLock schedulerLock;

  @Value("${app.payments.reconciliation.batch-size:20}")
  private int batchSize = 20;

  @Value("${app.payments.reconciliation.spacing-ms:1500}")
  private long spacingMs = 1500;

  @Scheduled(
      fixedDelayString = "${app.payments.reconciliation.delay-ms:120000}",
      initialDelayString = "${app.payments.reconciliation.initial-delay-ms:120000}")
  public void reconcileOpenIntents() {
    try {
      schedulerLock.runExclusive(
          "payment-reconciliation",
          Duration.ofMinutes(15),
          () -> {
            int queried = paymentService.reconcileOpenIntents(batchSize, spacingMs);
            if (queried > 0) {
              log.info("Payment reconciliation queried {} open intent(s)", queried);
            }
          });
    } catch (RuntimeException ex) {
      log.error("Payment reconciliation run failed: {}", ex.getClass().getSimpleName());
    }
  }
}
