package kz.hrms.splitupauth.scheduler;

import kz.hrms.splitupauth.service.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily refresh of the FX → KZT snapshot. Spaced away from the other
 * cleanup crons (02:00 / 03:00) and from typical bank business hours so a
 * transient upstream blip doesn't collide with login bursts.
 */
@Component
@RequiredArgsConstructor
public class FxRateScheduler {

    private final ExchangeRateService exchangeRateService;

    /** Daily at 04:15 Almaty. Cron uses the Spring scheduler thread's TZ (Asia/Almaty). */
    @Scheduled(cron = "0 15 4 * * ?")
    public void refreshDaily() {
        exchangeRateService.refresh();
    }
}
