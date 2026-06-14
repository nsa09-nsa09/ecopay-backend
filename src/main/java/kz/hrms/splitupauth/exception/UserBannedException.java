package kz.hrms.splitupauth.exception;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Thrown when authentication succeeds against credentials but the account
 * status is BANNED. Carries reason + timestamp so the global handler can emit
 * a structured payload the frontend renders instead of a generic 403 message.
 */
@Getter
public class UserBannedException extends RuntimeException {

    private final String reason;
    private final LocalDateTime bannedAt;

    public UserBannedException(String message) {
        this(message, null, null);
    }

    public UserBannedException(String message, String reason, LocalDateTime bannedAt) {
        super(message);
        this.reason = reason;
        this.bannedAt = bannedAt;
    }
}
