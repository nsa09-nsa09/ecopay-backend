package kz.hrms.splitupauth.service;

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

  @Scheduled(fixedDelayString = "${app.payout.dispatch-delay-ms:60000}")
  public void dispatchDuePayouts() {
    payoutService.processPendingPayouts();
    payoutService.reconcilePendingProviderPayouts();
  }
}
