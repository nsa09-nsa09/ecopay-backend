package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.UserBannedException;
import org.junit.jupiter.api.Test;

class AccountRestrictionServiceTest {
  private final AccountRestrictionService service = new AccountRestrictionService();

  @Test
  void scheduledStartAndTemporaryEndAreEnforcedWithoutScheduler() {
    LocalDateTime start = LocalDateTime.of(2026, 9, 25, 9, 0);
    LocalDateTime end = start.plusDays(7);
    User user =
        User.builder()
            .status(UserStatus.ACTIVE)
            .banStartsAt(start)
            .banUntil(end)
            .banReason("Investigation")
            .build();
    assertFalse(service.isRestricted(user, start.minusNanos(1)));
    assertTrue(service.isRestricted(user, start));
    assertTrue(service.isRestricted(user, end.minusNanos(1)));
    assertFalse(service.isRestricted(user, end));
    user.setStatus(UserStatus.BANNED);
    assertFalse(service.isRestricted(user, end));
  }

  @Test
  void activeRestrictionIncludesReasonAndDates() {
    LocalDateTime start = LocalDateTime.now().minusHours(1);
    LocalDateTime end = LocalDateTime.now().plusHours(1);
    User user =
        User.builder()
            .status(UserStatus.ACTIVE)
            .banStartsAt(start)
            .banUntil(end)
            .banReason("Fraud")
            .build();
    UserBannedException error =
        assertThrows(UserBannedException.class, () -> service.requireAllowed(user));
    assertEquals("Fraud", error.getReason());
    assertEquals(start, error.getBanStartsAt());
    assertEquals(end, error.getBanUntil());
  }

  @Test
  void legacyIndefiniteBanStillBlocks() {
    User user = User.builder().status(UserStatus.BANNED).build();
    assertTrue(service.isRestricted(user, LocalDateTime.now()));
  }
}
