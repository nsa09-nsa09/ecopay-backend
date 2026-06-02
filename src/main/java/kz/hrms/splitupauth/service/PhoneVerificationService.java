package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.entity.PhoneVerification;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.InvalidVerificationCodeException;
import kz.hrms.splitupauth.exception.PhoneAlreadyExistsException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.exception.TooManySmsAttemptsException;
import kz.hrms.splitupauth.exception.VerificationCodeExpiredException;
import kz.hrms.splitupauth.repository.PhoneVerificationRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.sms.SmsProperties;
import kz.hrms.splitupauth.sms.SmsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PhoneVerificationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PhoneVerificationRepository verificationRepository;
    private final UserRepository userRepository;
    private final SmsService smsService;
    private final PasswordEncoder passwordEncoder;
    private final SmsProperties smsProperties;

    /**
     * Dev/test only: a master code that verifies any phone without the real SMS code
     * (the dev SMS provider only logs codes, so automated flows can't read them).
     * Empty in prod → disabled.
     */
    @Value("${app.phone.dev-bypass-code:}")
    private String devBypassCode;

    /**
     * Issue a new verification code for the given phone and user. Enforces:
     *  - resend cooldown ({@link SmsProperties#getResendCooldownSeconds()})
     *  - hourly cap ({@link SmsProperties#getMaxAttemptsPerHour()})
     *  - phone uniqueness against other users
     */
    @Transactional
    public void requestCode(User user, String phone) {
        if (user.getPhoneVerifiedAt() != null && phone.equals(user.getPhone())) {
            // Already verified — no need to send again.
            return;
        }

        Optional<User> existingPhoneOwner = userRepository.findByPhone(phone);
        if (existingPhoneOwner.isPresent()
                && !existingPhoneOwner.get().getId().equals(user.getId())) {
            throw new PhoneAlreadyExistsException("Phone is already registered to another user");
        }

        LocalDateTime now = LocalDateTime.now();

        long attemptsLastHour = verificationRepository.countByPhoneAndCreatedAtAfter(
                phone, now.minusHours(1));
        if (attemptsLastHour >= smsProperties.getMaxAttemptsPerHour()) {
            throw new TooManySmsAttemptsException(
                    "Too many SMS code requests. Try again later.");
        }

        Optional<PhoneVerification> latest =
                verificationRepository.findTopByPhoneOrderByCreatedAtDesc(phone);
        if (latest.isPresent()) {
            LocalDateTime nextAllowed = latest.get().getCreatedAt()
                    .plusSeconds(smsProperties.getResendCooldownSeconds());
            if (now.isBefore(nextAllowed)) {
                throw new TooManySmsAttemptsException(
                        "Please wait before requesting another code.");
            }
        }

        // Update user.phone if changed (will be confirmed by verify).
        if (!phone.equals(user.getPhone())) {
            user.setPhone(phone);
            user.setPhoneVerifiedAt(null);
            user.setOwnerVerified(false);
            userRepository.save(user);
        }

        String code = generate6DigitCode();
        PhoneVerification verification = PhoneVerification.builder()
                .user(user)
                .phone(phone)
                .codeHash(passwordEncoder.encode(code))
                .expiresAt(now.plusSeconds(smsProperties.getCodeTtlSeconds()))
                .attempts(0)
                .createdAt(now)
                .build();
        verificationRepository.save(verification);

        smsService.sendVerificationCode(phone, code);
    }

    /**
     * Verify a code that the user typed in. Marks user.phone_verified_at on success.
     */
    @Transactional
    public void verifyCode(User user, String phone, String code) {
        // Dev/test bypass: accept a configured master code without the real SMS code.
        if (devBypassCode != null && !devBypassCode.isBlank() && devBypassCode.equals(code)) {
            user.setPhone(phone);
            user.setPhoneVerifiedAt(LocalDateTime.now());
            user.setOwnerVerified(true);
            userRepository.save(user);
            log.warn("[DEV] phone {} verified via dev-bypass code for user {}", phone, user.getId());
            return;
        }

        PhoneVerification verification = verificationRepository
                .findTopByUserAndPhoneAndVerifiedAtIsNullOrderByCreatedAtDesc(user, phone)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No active verification code for this phone"));

        if (verification.isExpired()) {
            throw new VerificationCodeExpiredException("Verification code expired");
        }

        if (verification.getAttempts() >= smsProperties.getMaxVerifyAttempts()) {
            throw new TooManySmsAttemptsException(
                    "Too many invalid attempts. Request a new code.");
        }

        if (!passwordEncoder.matches(code, verification.getCodeHash())) {
            verification.setAttempts(verification.getAttempts() + 1);
            verificationRepository.save(verification);
            throw new InvalidVerificationCodeException("Invalid verification code");
        }

        verification.setVerifiedAt(LocalDateTime.now());
        verificationRepository.save(verification);

        user.setPhone(phone);
        user.setPhoneVerifiedAt(LocalDateTime.now());
        user.setOwnerVerified(true);
        userRepository.save(user);
    }

    private String generate6DigitCode() {
        int n = RANDOM.nextInt(1_000_000);
        return String.format("%06d", n);
    }
}
