package kz.hrms.splitupauth.exception;

import java.time.LocalDateTime;
import lombok.Getter;

/**
 * Thrown when authentication succeeds against credentials but the account status is BANNED. Carries
 * reason + timestamp so the global handler can emit a structured payload the frontend renders
 * instead of a generic 403 message.
 */
@Getter
public class UserBannedException extends RuntimeException {

  private final String reason;
  private final LocalDateTime bannedAt;
  private final LocalDateTime banStartsAt;
  private final LocalDateTime banUntil;

  public UserBannedException(String message) {
    this(message, null, null);
  }

  public UserBannedException(String message, String reason, LocalDateTime bannedAt) {
    this(message, reason, bannedAt, null, null);
  }

  public UserBannedException(
      String message,
      String reason,
      LocalDateTime bannedAt,
      LocalDateTime banStartsAt,
      LocalDateTime banUntil) {
    super(message);
    this.reason = reason;
    this.bannedAt = bannedAt;
    this.banStartsAt = banStartsAt;
    this.banUntil = banUntil;
  }
}
