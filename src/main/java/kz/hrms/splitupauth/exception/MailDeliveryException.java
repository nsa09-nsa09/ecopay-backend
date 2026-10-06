package kz.hrms.splitupauth.exception;

/**
 * The mail server refused or could not be reached after the configured retries. Distinct from
 * {@link InvalidEmailException} — nothing is wrong with the address, our side failed — so it maps
 * to 503 and a "try again in a moment" message instead of a field error.
 */
public class MailDeliveryException extends RuntimeException {

  public MailDeliveryException(String message) {
    super(message);
  }

  public MailDeliveryException(String message, Throwable cause) {
    super(message, cause);
  }
}
