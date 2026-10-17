package kz.hrms.splitupauth.service;

import java.time.LocalDateTime;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.UserBannedException;
import org.springframework.stereotype.Service;

@Service
public class AccountRestrictionService {
  public boolean isRestricted(User user, LocalDateTime now) {
    if (user.getStatus() == UserStatus.DELETED) return false;
    LocalDateTime start = user.getBanStartsAt();
    LocalDateTime end = user.getBanUntil();
    if (start == null) return user.getStatus() == UserStatus.BANNED;
    return !start.isAfter(now) && (end == null || end.isAfter(now));
  }

  public void requireAllowed(User user) {
    if (isRestricted(user, LocalDateTime.now())) {
      throw new UserBannedException(
          "Your account has been banned",
          user.getBanReason(),
          user.getBannedAt(),
          user.getBanStartsAt(),
          user.getBanUntil());
    }
  }
}
