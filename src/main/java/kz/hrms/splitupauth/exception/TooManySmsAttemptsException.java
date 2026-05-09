package kz.hrms.splitupauth.exception;

public class TooManySmsAttemptsException extends RuntimeException {
    public TooManySmsAttemptsException(String message) {
        super(message);
    }
}
