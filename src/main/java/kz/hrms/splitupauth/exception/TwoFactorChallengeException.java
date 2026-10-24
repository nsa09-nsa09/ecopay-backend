package kz.hrms.splitupauth.exception;

public class TwoFactorChallengeException extends RuntimeException {

  private final String code;

  public TwoFactorChallengeException(String message) {
    super(message);
    this.code = null;
  }

  public TwoFactorChallengeException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
