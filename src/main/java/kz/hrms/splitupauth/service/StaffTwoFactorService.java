package kz.hrms.splitupauth.service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.UUID;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.StaffTwoFactorChallenge;
import kz.hrms.splitupauth.entity.StaffTwoFactorChallengeStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.TwoFactorChallengeException;
import kz.hrms.splitupauth.repository.StaffTwoFactorChallengeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issues, verifies, resends and cleans up 2FA challenges for ADMIN / SUPPORT staff accounts. Only
 * the hashed OTP is persisted; the plaintext code is sent to the user via the existing email
 * service and is never logged.
 */
@Service
@Slf4j
public class StaffTwoFactorService {

  private static final SecureRandom RANDOM = new SecureRandom();

  public static final int CODE_TTL_MINUTES = 10;
  public static final int RESEND_COOLDOWN_SECONDS = 30;
  public static final int MAX_ATTEMPTS = 5;
  public static final int CHALLENGE_MAX_LIFETIME_MINUTES = 30;
  public static final int MAX_RESENDS = 5;

  private final StaffTwoFactorChallengeRepository challengeRepository;
  private final PasswordEncoder passwordEncoder;
  private final EmailService emailService;
  private final PlatformTransactionManager transactionManager;

  public StaffTwoFactorService(
      StaffTwoFactorChallengeRepository challengeRepository,
      PasswordEncoder passwordEncoder,
      EmailService emailService) {
    this(challengeRepository, passwordEncoder, emailService, null);
  }

  @Autowired
  public StaffTwoFactorService(
      StaffTwoFactorChallengeRepository challengeRepository,
      PasswordEncoder passwordEncoder,
      EmailService emailService,
      @Autowired(required = false) PlatformTransactionManager transactionManager) {
    this.challengeRepository = challengeRepository;
    this.passwordEncoder = passwordEncoder;
    this.emailService = emailService;
    this.transactionManager = transactionManager;
  }

  private <T> T inTransaction(java.util.function.Supplier<T> action) {
    if (transactionManager != null) {
      return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
    return action.get();
  }

  private void inTransactionWithoutResult(Runnable action) {
    if (transactionManager != null) {
      new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
    } else {
      action.run();
    }
  }

  /** Returns true for accounts that must complete a 2FA step before tokens are issued. */
  public boolean requiresTwoFactor(User user) {
    Role role = user.getRole();
    return role == Role.ADMIN || role == Role.SUPPORT;
  }

  /** Create a new challenge for the given staff user and email the plaintext code. */
  public StaffTwoFactorChallenge createChallenge(User user) {
    if (!requiresTwoFactor(user)) {
      throw new TwoFactorChallengeException("2FA is not required for this account");
    }

    // Backstop for the guard in AdminUserController.changeRole. Reachable for
    // accounts promoted before that guard existed, or seeded straight into the
    // DB. Without it MimeMessageHelper.setTo(null) raises IllegalArgumentException
    // — unmapped, so the operator gets an opaque 500 instead of being told what
    // is actually wrong with the account.
    if (user.getEmail() == null || user.getEmail().isBlank()) {
      throw new TwoFactorChallengeException(
          "This staff account has no email address to send the sign-in code to");
    }

    String code = generate6DigitCode();
    LocalDateTime now = LocalDateTime.now();

    StaffTwoFactorChallenge challenge =
        StaffTwoFactorChallenge.builder()
            .id(UUID.randomUUID().toString())
            .user(user)
            .codeHash(passwordEncoder.encode(code))
            .status(StaffTwoFactorChallengeStatus.PENDING)
            .attempts(0)
            .expiresAt(now.plusMinutes(CODE_TTL_MINUTES))
            .createdAt(now)
            .lastSentAt(now)
            .build();

    // Persist and commit the challenge in its own isolated transaction BEFORE sending the email
    // so slow SMTP network I/O is never held inside a database transaction (which causes
    // CockroachDB
    // RETRY_SERIALIZABLE / CannotAcquireLockException).
    StaffTwoFactorChallenge saved = inTransaction(() -> challengeRepository.save(challenge));

    try {
      emailService.sendStaffTwoFactorCode(user.getEmail(), code, MailLocale.from(user.getLocale()));
    } catch (Exception e) {
      inTransactionWithoutResult(
          () -> {
            try {
              challengeRepository.deleteById(saved.getId());
            } catch (Exception cleanupEx) {
              log.warn(
                  "Failed to clean up challenge after failed email delivery: {}",
                  cleanupEx.getMessage());
            }
          });
      throw e;
    }

    return saved;
  }

  /**
   * Verify a code provided by the user. On success the challenge is marked VERIFIED so it cannot be
   * reused; on failure the attempt counter is bumped and the challenge is marked FAILED once max
   * attempts is reached.
   *
   * <p>{@code noRollbackFor} is load-bearing: the wrong-code / expired branches bump the attempt
   * counter (or flip the status) and then throw. A plain {@code @Transactional} would roll those
   * writes back together with the exception and the lockout would never trip. Same reasoning as in
   * {@link PhoneVerificationService#verifyCode}.
   *
   * @return the staff user whose challenge was successfully verified.
   */
  @Transactional(noRollbackFor = TwoFactorChallengeException.class)
  public User verifyChallenge(String challengeId, String submittedCode) {
    StaffTwoFactorChallenge challenge =
        challengeRepository
            .findById(challengeId)
            .orElseThrow(
                () -> new TwoFactorChallengeException("Invalid or expired verification code"));

    if (challenge.getStatus() == StaffTwoFactorChallengeStatus.VERIFIED || challenge.isUsed()) {
      throw new TwoFactorChallengeException("Invalid or expired verification code");
    }

    if (challenge.isExpired()) {
      challenge.setStatus(StaffTwoFactorChallengeStatus.EXPIRED);
      challengeRepository.save(challenge);
      throw new TwoFactorChallengeException(
          "TWO_FACTOR_EXPIRED", "Challenge expired. Please sign in again.");
    }

    if (challenge.getAttempts() >= MAX_ATTEMPTS
        || challenge.getStatus() == StaffTwoFactorChallengeStatus.FAILED) {
      throw new TwoFactorChallengeException("Too many invalid attempts. Please sign in again.");
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
   * Rotate the code for an existing challenge. Each resend issues a new code with a fresh 10-minute
   * expiry, but the overall challenge lifetime (from createdAt) must not exceed 30 minutes and the
   * total number of resends must not exceed 5.
   */
  public StaffTwoFactorChallenge resendChallenge(String challengeId) {
    record ResendPayload(
        StaffTwoFactorChallenge challenge, String email, String code, String locale) {}

    ResendPayload payload =
        inTransaction(
            () -> {
              StaffTwoFactorChallenge challenge =
                  challengeRepository
                      .findById(challengeId)
                      .orElseThrow(
                          () ->
                              new TwoFactorChallengeException(
                                  "TWO_FACTOR_EXPIRED",
                                  "Challenge expired. Please sign in again."));

              if (challenge.getStatus() != StaffTwoFactorChallengeStatus.PENDING
                  || challenge.isUsed()) {
                throw new TwoFactorChallengeException(
                    "TWO_FACTOR_EXPIRED", "Challenge expired. Please sign in again.");
              }

              LocalDateTime now = LocalDateTime.now();

              // Overall challenge lifetime check (30 minutes from creation).
              LocalDateTime absoluteDeadline =
                  challenge.getCreatedAt().plusMinutes(CHALLENGE_MAX_LIFETIME_MINUTES);
              if (now.isAfter(absoluteDeadline)) {
                challenge.setStatus(StaffTwoFactorChallengeStatus.EXPIRED);
                challengeRepository.save(challenge);
                throw new TwoFactorChallengeException(
                    "TWO_FACTOR_EXPIRED", "Challenge expired. Please sign in again.");
              }

              // Max resends check.
              int currentResends =
                  challenge.getResendCount() != null ? challenge.getResendCount() : 0;
              if (currentResends >= MAX_RESENDS) {
                challenge.setStatus(StaffTwoFactorChallengeStatus.EXPIRED);
                challengeRepository.save(challenge);
                throw new TwoFactorChallengeException(
                    "TWO_FACTOR_EXPIRED", "Too many resend attempts. Please sign in again.");
              }

              LocalDateTime nextAllowed =
                  challenge.getLastSentAt().plusSeconds(RESEND_COOLDOWN_SECONDS);
              if (now.isBefore(nextAllowed)) {
                throw new TwoFactorChallengeException(
                    "Please wait before requesting another code.");
              }

              if (challenge.isExpired()) {
                challenge.setStatus(StaffTwoFactorChallengeStatus.EXPIRED);
                challengeRepository.save(challenge);
                throw new TwoFactorChallengeException(
                    "TWO_FACTOR_EXPIRED", "Challenge expired. Please sign in again.");
              }

              String code = generate6DigitCode();
              challenge.setCodeHash(passwordEncoder.encode(code));
              challenge.setLastSentAt(now);
              challenge.setResendCount(currentResends + 1);
              // New code gets a fresh 10-minute window, capped at absolute deadline.
              LocalDateTime newExpiry = now.plusMinutes(CODE_TTL_MINUTES);
              if (newExpiry.isAfter(absoluteDeadline)) {
                newExpiry = absoluteDeadline;
              }
              challenge.setExpiresAt(newExpiry);
              challenge = challengeRepository.save(challenge);

              User user = challenge.getUser();
              return new ResendPayload(
                  challenge,
                  user != null ? user.getEmail() : null,
                  code,
                  user != null ? user.getLocale() : null);
            });

    try {
      emailService.sendStaffTwoFactorCode(
          payload.email(), payload.code(), MailLocale.from(payload.locale()));
    } catch (Exception e) {
      log.error("SMTP delivery failed during 2FA resend: {}", e.getMessage());
      throw new TwoFactorChallengeException(
          "EMAIL_SEND_FAILED", "Failed to send verification email. Please try again.");
    }

    return payload.challenge();
  }

  /** Purge challenges whose lifetime has elapsed. */
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
