package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.StaffTwoFactorChallenge;
import kz.hrms.splitupauth.entity.StaffTwoFactorChallengeStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.TwoFactorChallengeException;
import kz.hrms.splitupauth.repository.StaffTwoFactorChallengeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Issues, verifies, resends and cleans up 2FA challenges for ADMIN / SUPPORT
 * staff accounts. Only the hashed OTP is persisted; the plaintext code is sent
 * to the user via the existing email service and is never logged.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StaffTwoFactorService {

    private static final SecureRandom RANDOM = new SecureRandom();

    public static final int CODE_TTL_MINUTES = 10;
    public static final int RESEND_COOLDOWN_SECONDS = 30;
    public static final int MAX_ATTEMPTS = 5;

    private final StaffTwoFactorChallengeRepository challengeRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    /**
     * Returns true for accounts that must complete a 2FA step before tokens are issued.
     */
    public boolean requiresTwoFactor(User user) {
        Role role = user.getRole();
        return role == Role.ADMIN || role == Role.SUPPORT;
    }

    /**
     * Create a new challenge for the given staff user and email the plaintext code.
     */
    @Transactional
    public StaffTwoFactorChallenge createChallenge(User user) {
        if (!requiresTwoFactor(user)) {
            throw new TwoFactorChallengeException("2FA is not required for this account");
        }

        String code = generate6DigitCode();
        LocalDateTime now = LocalDateTime.now();

        StaffTwoFactorChallenge challenge = StaffTwoFactorChallenge.builder()
                .id(UUID.randomUUID().toString())
                .user(user)
                .codeHash(passwordEncoder.encode(code))
                .status(StaffTwoFactorChallengeStatus.PENDING)
                .attempts(0)
                .expiresAt(now.plusMinutes(CODE_TTL_MINUTES))
                .createdAt(now)
                .lastSentAt(now)
                .build();

        challenge = challengeRepository.save(challenge);

        try {
            emailService.sendStaffTwoFactorCode(user.getEmail(), code);
        } catch (RuntimeException ex) {
            log.warn("Failed to send staff 2FA email to user id={}: {}", user.getId(), ex.getMessage());
        }

        return challenge;
    }

    /**
     * Verify a code provided by the user. On success the challenge is marked
     * VERIFIED so it cannot be reused; on failure the attempt counter is bumped
     * and the challenge is marked FAILED once max attempts is reached.
     *
     * @return the staff user whose challenge was successfully verified.
     */
    @Transactional
    public User verifyChallenge(String challengeId, String submittedCode) {
        StaffTwoFactorChallenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new TwoFactorChallengeException(
                        "Invalid or expired verification code"));

        if (challenge.getStatus() == StaffTwoFactorChallengeStatus.VERIFIED
                || challenge.isUsed()) {
            throw new TwoFactorChallengeException(
                    "Invalid or expired verification code");
        }

        if (challenge.isExpired()) {
            challenge.setStatus(StaffTwoFactorChallengeStatus.EXPIRED);
            challengeRepository.save(challenge);
            throw new TwoFactorChallengeException(
                    "Invalid or expired verification code");
        }

        if (challenge.getAttempts() >= MAX_ATTEMPTS
                || challenge.getStatus() == StaffTwoFactorChallengeStatus.FAILED) {
            throw new TwoFactorChallengeException(
                    "Too many invalid attempts. Please sign in again.");
        }

        boolean matches = passwordEncoder.matches(submittedCode, challenge.getCodeHash());
        if (!matches) {
            int newAttempts = challenge.getAttempts() + 1;
            challenge.setAttempts(newAttempts);
            if (newAttempts >= MAX_ATTEMPTS) {
                challenge.setStatus(StaffTwoFactorChallengeStatus.FAILED);
            }
            challengeRepository.save(challenge);
            throw new TwoFactorChallengeException("Invalid or expired verification code");
        }

        challenge.setStatus(StaffTwoFactorChallengeStatus.VERIFIED);
        challenge.setUsedAt(LocalDateTime.now());
        challengeRepository.save(challenge);

        return challenge.getUser();
    }

    /**
     * Rotate the code for an existing challenge. Cooldown protected to prevent
     * email flooding. Returns the (possibly mutated) challenge.
     */
    @Transactional
    public StaffTwoFactorChallenge resendChallenge(String challengeId) {
        StaffTwoFactorChallenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new TwoFactorChallengeException(
                        "Invalid or expired verification code"));

        if (challenge.getStatus() != StaffTwoFactorChallengeStatus.PENDING
                || challenge.isUsed()) {
            throw new TwoFactorChallengeException(
                    "Challenge cannot be resent. Please sign in again.");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextAllowed = challenge.getLastSentAt()
                .plusSeconds(RESEND_COOLDOWN_SECONDS);
        if (now.isBefore(nextAllowed)) {
            throw new TwoFactorChallengeException(
                    "Please wait before requesting another code.");
        }

        if (challenge.isExpired()) {
            challenge.setStatus(StaffTwoFactorChallengeStatus.EXPIRED);
            challengeRepository.save(challenge);
            throw new TwoFactorChallengeException(
                    "Challenge cannot be resent. Please sign in again.");
        }

        String code = generate6DigitCode();
        challenge.setCodeHash(passwordEncoder.encode(code));
        challenge.setLastSentAt(now);
        // Keep the original expires_at so attackers cannot extend the window
        // indefinitely by spamming resends.
        challenge = challengeRepository.save(challenge);

        try {
            emailService.sendStaffTwoFactorCode(challenge.getUser().getEmail(), code);
        } catch (RuntimeException ex) {
            log.warn("Failed to re-send staff 2FA email to user id={}: {}",
                    challenge.getUser().getId(), ex.getMessage());
        }

        return challenge;
    }

    /**
     * Purge challenges whose lifetime has elapsed.
     */
    @Transactional
    public int cleanupExpired() {
        return challengeRepository.deleteByExpiresAtBefore(LocalDateTime.now());
    }

    public static String maskEmail(String email) {
        if (email == null || email.isEmpty()) {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "*" + (at >= 0 ? email.substring(at) : "");
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) {
            return local.charAt(0) + "*" + domain;
        }
        return local.charAt(0) + repeat('*', local.length() - 1) + domain;
    }

    private static String repeat(char c, int n) {
        if (n <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private String generate6DigitCode() {
        int n = RANDOM.nextInt(1_000_000);
        return String.format("%06d", n);
    }
}
