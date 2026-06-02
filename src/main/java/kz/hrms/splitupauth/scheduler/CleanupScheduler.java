package kz.hrms.splitupauth.scheduler;

import kz.hrms.splitupauth.service.PaymentService;
import kz.hrms.splitupauth.service.RateLimitService;
import kz.hrms.splitupauth.service.RefreshTokenService;
import kz.hrms.splitupauth.service.StaffTwoFactorService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CleanupScheduler {

    private final RefreshTokenService refreshTokenService;
    private final RateLimitService rateLimitService;
    private final StaffTwoFactorService staffTwoFactorService;
    private final PaymentService paymentService;

    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupExpiredTokens() {
        refreshTokenService.cleanupExpiredTokens();
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupOldLoginAttempts() {
        rateLimitService.cleanupOldAttempts();
    }

    @Scheduled(cron = "0 30 3 * * ?")
    public void cleanupExpiredStaffTwoFactorChallenges() {
        staffTwoFactorService.cleanupExpired();
    }

    /** Every 5 minutes: fail PENDING payment intents that passed their expiry. */
    @Scheduled(fixedDelayString = "${app.scheduler.intent-expiry-delay-ms:300000}")
    public void expireStalePaymentIntents() {
        paymentService.expireStalePendingIntents();
    }
}
