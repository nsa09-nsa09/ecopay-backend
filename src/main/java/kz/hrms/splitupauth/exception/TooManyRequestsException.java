package kz.hrms.splitupauth.exception;

/** Generic rate-limit breach → HTTP 429. */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) {
        super(message);
    }
}
