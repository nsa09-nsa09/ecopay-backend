package kz.hrms.splitupauth.payment.gateway.freedom;

public class FreedomPayException extends RuntimeException {
    public FreedomPayException(String message) {
        super(message);
    }

    public FreedomPayException(String message, Throwable cause) {
        super(message, cause);
    }
}
