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

  @Scheduled(fixedDelayString = "${app.refunds.retry-delay-ms:60000}")
  public void dispatchApprovedRefunds() {
    refundService.processPendingRefunds();
  }
}
