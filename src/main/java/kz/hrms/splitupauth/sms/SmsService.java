package kz.hrms.splitupauth.sms;

public interface SmsService {

    /**
     * Send a one-time verification code to a phone number in international format
     * (e.g. +77001234567).
     *
     * Implementations must be non-blocking-friendly: they may use the calling
     * thread but must not perform unbounded retries.
     *
     * @param phone E.164 phone number
     * @param code  6-digit numeric code
     */
    void sendVerificationCode(String phone, String code);
}
