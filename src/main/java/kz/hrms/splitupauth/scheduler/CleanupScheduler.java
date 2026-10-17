package kz.hrms.splitupauth.scheduler;

import java.time.Duration;
import kz.hrms.splitupauth.service.JdbcRateLimiter;
import kz.hrms.splitupauth.service.PaymentService;
import kz.hrms.splitupauth.service.RateLimitService;
import kz.hrms.splitupauth.service.RefreshTokenService;
import kz.hrms.splitupauth.service.StaffTwoFactorService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CleanupScheduler {

  private final RefreshTokenService refreshTokenService;
  private final RateLimitService rateLimitService;
  private final StaffTwoFactorService staffTwoFactorService;
  private final PaymentService paymentService;
  private final SchedulerLock schedulerLock;
  private final ObjectProvider<JdbcRateLimiter> jdbcRateLimiter;

  @Scheduled(cron = "0 0 2 * * ?")
  public void cleanupExpiredTokens() {
    schedulerLock.runExclusive(
        "cleanup-refresh-tokens",
        Duration.ofMinutes(30),
        refreshTokenService::cleanupExpiredTokens);
  }

  @Scheduled(cron = "0 0 3 * * ?")
  public void cleanupOldLoginAttempts() {
    schedulerLock.runExclusive(
        "cleanup-login-attempts", Duration.ofMinutes(30), rateLimitService::cleanupOldAttempts);
  }

  @Scheduled(cron = "0 30 3 * * ?")
  public void cleanupExpiredStaffTwoFactorChallenges() {
    schedulerLock.runExclusive(
        "cleanup-staff-2fa", Duration.ofMinutes(30), staffTwoFactorService::cleanupExpired);
  }

  /** Every 5 minutes: fail PENDING payment intents that passed their expiry. */
  @Scheduled(fixedDelayString = "${app.scheduler.intent-expiry-delay-ms:300000}")
  public void expireStalePaymentIntents() {
    schedulerLock.runExclusive(
        "payment-intent-expiry", Duration.ofMinutes(10), paymentService::expireStalePendingIntents);
  }

  /** Hourly: drop rate-limit windows that can no longer affect a decision (jdbc store only). */
  @Scheduled(cron = "0 7 * * * ?")
  public void purgeRateLimitCounters() {
    JdbcRateLimiter limiter = jdbcRateLimiter.getIfAvailable();
    if (limiter == null) {
      return;
    }
    schedulerLock.runExclusive(
        "cleanup-rate-limit-counters", Duration.ofMinutes(15), limiter::purgeExpired);
  }
}
