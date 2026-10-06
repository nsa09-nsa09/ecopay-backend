package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import kz.hrms.splitupauth.exception.ResourceConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class LiveMoneyGuardTest {

  @Test
  void productionSafeModeRejectsNewCharges() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");
    environment.setProperty("app.money.live-enabled", "false");

    assertThrows(
        ResourceConflictException.class,
        () -> new LiveMoneyGuard(environment).requireEnabledForNewCharge());
  }

  @Test
  void developmentAndExplicitLiveModeAllowNewCharges() {
    MockEnvironment dev = new MockEnvironment();
    dev.setActiveProfiles("dev");
    MockEnvironment live = new MockEnvironment();
    live.setActiveProfiles("prod");
    live.setProperty("app.money.live-enabled", "true");

    assertDoesNotThrow(() -> new LiveMoneyGuard(dev).requireEnabledForNewCharge());
    assertDoesNotThrow(() -> new LiveMoneyGuard(live).requireEnabledForNewCharge());
  }
}
