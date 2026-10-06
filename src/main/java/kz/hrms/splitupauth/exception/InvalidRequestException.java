package kz.hrms.splitupauth.exception;

public class InvalidRequestException extends RuntimeException {

  /** Stable SCREAMING_SNAKE error code for the frontend; null for generic/unclassified cases. */
  private final String code;

  public InvalidRequestException(String message) {
    super(message);
    this.code = null;
  }

  public InvalidRequestException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
