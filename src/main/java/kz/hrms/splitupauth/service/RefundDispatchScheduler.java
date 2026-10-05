package kz.hrms.splitupauth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs provider refund dispatch only after the explicit refund money switch is enabled. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.money.refund-dispatch-enabled", havingValue = "true")
public class RefundDispatchScheduler {

  private final RefundService refundService;
  private final kz.hrms.splitupauth.scheduler.SchedulerLock schedulerLock;

  @Scheduled(fixedDelayString = "${app.refunds.retry-delay-ms:60000}")
  public void dispatchApprovedRefunds() {
    schedulerLock.runExclusive(
        kz.hrms.splitupauth.scheduler.SchedulerLock.Key.REFUND_DISPATCH,
        refundService::processPendingRefunds);
  }
}
