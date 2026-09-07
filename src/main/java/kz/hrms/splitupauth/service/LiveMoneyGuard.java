package kz.hrms.splitupauth.service;

import java.util.Arrays;
import kz.hrms.splitupauth.exception.ResourceConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** Prevents new real-money entry points while a production deployment is in safe mode. */
@Service
@RequiredArgsConstructor
public class LiveMoneyGuard {

  private final Environment environment;

  public void requireEnabledForNewCharge() {
    boolean production =
        Arrays.stream(environment.getActiveProfiles()).anyMatch("prod"::equalsIgnoreCase);
    boolean enabled =
        environment.getProperty("app.money.live-enabled", Boolean.class, Boolean.FALSE);
    if (production && !enabled) {
      throw new ResourceConflictException(
          "PAYMENTS_TEMPORARILY_DISABLED", "New payments are temporarily disabled");
    }
  }
}
