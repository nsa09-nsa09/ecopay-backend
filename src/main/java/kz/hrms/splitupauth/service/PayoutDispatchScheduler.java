package kz.hrms.splitupauth.service;

import java.time.Duration;
import kz.hrms.splitupauth.scheduler.SchedulerLock;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Enables outbound owner payouts only when the dedicated money-movement switch is explicitly on.
 * Keeping scheduling outside {@link PayoutService} prevents an accidental deployment from sending
 * money merely because the application started.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.money.payout-dispatch-enabled", havingValue = "true")
public class PayoutDispatchScheduler {

  private final PayoutService payoutService;
  private final SchedulerLock schedulerLock;

  @Scheduled(fixedDelayString = "${app.payout.dispatch-delay-ms:60000}")
  public void dispatchDuePayouts() {
    // Row leases, FOR UPDATE claims and the DB immutability triggers already make a concurrent
    // second dispatcher harmless; the cluster lock removes the wasted duplicate scans and the
    // duplicate provider status polls.
    schedulerLock.runExclusive(
        "payout-dispatch",
        Duration.ofMinutes(10),
        () -> {
          payoutService.processPendingPayouts();
          payoutService.reconcilePendingProviderPayouts();
        });
  }
}
