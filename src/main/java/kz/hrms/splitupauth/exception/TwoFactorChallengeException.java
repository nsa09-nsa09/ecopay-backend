package kz.hrms.splitupauth.exception;

public class TwoFactorChallengeException extends RuntimeException {
    public TwoFactorChallengeException(String message) {
        super(message);
    }
}
