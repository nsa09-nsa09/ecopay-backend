package kz.hrms.splitupauth.exception;

import lombok.Getter;

/**
 * Email failed one of the pre-delivery checks (format or MX). Carries a stable {@code reason} code
 * so the frontend can render a specific message instead of a generic "invalid email", plus an
 * optional {@code suggestion} for likely typos (gmial.com → gmail.com).
 *
 * <p>Both fields are surfaced in the {@code errors} map by GlobalExceptionHandler.
 */
@Getter
public class InvalidEmailException extends RuntimeException {

  /** Stable, locale-agnostic reason code. Mirrored by the frontend's EmailErrorCode union. */
  public enum Reason {
    /** Structurally malformed: no @, no TDL, double dots, illegal characters, too long. */
    EMAIL_INVALID_FORMAT,
    /** Syntactically fine, but the domain publishes no MX (and no A) record. */
    EMAIL_DOMAIN_NOT_FOUND,
    /** DNS was unreachable or timed out — we could not decide either way. */
    EMAIL_DOMAIN_UNVERIFIABLE
  }

  private final Reason reason;

  /** Corrected address we believe the user meant, or {@code null} when we have no guess. */
  private final String suggestion;

  public InvalidEmailException(Reason reason, String message) {
    this(reason, message, null);
  }

  public InvalidEmailException(Reason reason, String message, String suggestion) {
    super(message);
    this.reason = reason;
    this.suggestion = suggestion;
  }
}
