package kz.hrms.splitupauth.service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.UUID;
import kz.hrms.splitupauth.dto.UserDto;
import kz.hrms.splitupauth.entity.EmailVerificationToken;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.InvalidVerificationCodeException;
import kz.hrms.splitupauth.exception.TooManyRequestsException;
import kz.hrms.splitupauth.exception.UserAlreadyExistsException;
import kz.hrms.splitupauth.exception.VerificationCodeExpiredException;
import kz.hrms.splitupauth.repository.EmailVerificationTokenRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds or changes the account email from the profile. Email is optional since phone registration
 * exists, so this flow is the only way a phone-registered account acquires one (needed for password
 * recovery and email notifications).
 *
 * <p>The new address is parked on {@link EmailVerificationToken#getPendingEmail()} and copied to
 * {@code users.email} only after the emailed one-time code (TTL {@value #CODE_TTL_MINUTES} min) or
 * the click-through link is confirmed. Until then the account keeps its previous email — a typo'd
 * or abandoned change can never lock the user out.
 */
@Service
@RequiredArgsConstructor
public class EmailChangeService {

  private static final SecureRandom RANDOM = new SecureRandom();

  static final int CODE_TTL_MINUTES = 30;

  /** Wrong-code attempts allowed before the change must be re-requested. */
  static final int MAX_CODE_ATTEMPTS = 5;

  /** Minimum seconds between two confirmation emails for the same account. */
  static final int RESEND_COOLDOWN_SECONDS = 60;

  /** Confirmation emails allowed per account per hour (anti-spam). */
  static final int MAX_SENDS_PER_HOUR = 5;

  private final UserRepository userRepository;
  private final EmailVerificationTokenRepository tokenRepository;
  private final PasswordEncoder passwordEncoder;
  private final EmailService emailService;
  private final UserMapper userMapper;
  private final EmailValidationService emailValidationService;

  /**
   * Step 1: validate the new address and email a one-time confirmation code to it.
   *
   * @param locale language for the confirmation email; also persisted on the account so later mail
   *     (password reset, notifications) matches
   */
  @Transactional
  public void requestChange(User user, String newEmail, MailLocale locale) {
    // The principal is loaded by JwtAuthenticationFilter outside any
    // transaction, so it arrives detached — dirty checking will not pick this
    // up and the save() has to be explicit.
    if (!locale.tag().equals(user.getLocale())) {
      user.setLocale(locale.tag());
      userRepository.save(user);
    }

    // Full pipeline: canonicalise, reject malformed addresses, and reject
    // domains with no MX before we spend a send on them. This is the main
    // entry point for new addresses, so it is where typos would otherwise
    // accumulate.
    String email = emailValidationService.normalizeAndValidateDeliverable(newEmail);

    if (email.equalsIgnoreCase(user.getEmail()) && Boolean.TRUE.equals(user.getEmailVerified())) {
      throw new InvalidRequestException("This email is already attached to your account");
    }

    userRepository
        .findByEmail(email)
        .filter(other -> !other.getId().equals(user.getId()))
        .ifPresent(
            other -> {
              throw new UserAlreadyExistsException("This email is already in use");
            });

    LocalDateTime now = LocalDateTime.now();

    long sendsLastHour = tokenRepository.countByUserAndCreatedAtAfter(user, now.minusHours(1));
    if (sendsLastHour >= MAX_SENDS_PER_HOUR) {
      throw new TooManyRequestsException("Too many confirmation emails. Try again later.");
    }

    tokenRepository
        .findTopByUserOrderByCreatedAtDesc(user)
        .filter(t -> t.getCreatedAt().isAfter(now.minusSeconds(RESEND_COOLDOWN_SECONDS)))
        .ifPresent(
            t -> {
              throw new TooManyRequestsException("Please wait before requesting another code.");
            });

    tokenRepository.deleteByUser(user);

    String token = UUID.randomUUID().toString();
    String code = generate6DigitCode();
    EmailVerificationToken verificationToken =
        EmailVerificationToken.builder()
            .token(token)
            .user(user)
            .pendingEmail(email)
            .codeHash(passwordEncoder.encode(code))
            .attempts(0)
            .expiresAt(now.plusMinutes(CODE_TTL_MINUTES))
            .used(false)
            .build();
    tokenRepository.save(verificationToken);

    emailService.sendEmailChangeConfirmation(email, token, code, MailLocale.from(user.getLocale()));
  }

  /** Step 2: confirm the code; only now is the address copied onto the account. */
  @Transactional
  public UserDto confirmChange(User user, String code) {
    EmailVerificationToken verificationToken =
        tokenRepository
            .findByUser(user)
            .orElseThrow(
                () -> new InvalidVerificationCodeException("Invalid or expired verification code"));

    if (verificationToken.getUsed()
        || verificationToken.getCodeHash() == null
        || verificationToken.getPendingEmail() == null) {
      throw new InvalidVerificationCodeException("Invalid or expired verification code");
    }

    if (verificationToken.isExpired()) {
      throw new VerificationCodeExpiredException(
          "Verification code expired. Please request a new one.");
    }

    if (verificationToken.getAttempts() >= MAX_CODE_ATTEMPTS) {
      throw new InvalidVerificationCodeException(
          "Too many invalid attempts. Please request a new code.");
    }

    if (!passwordEncoder.matches(code, verificationToken.getCodeHash())) {
      verificationToken.setAttempts(verificationToken.getAttempts() + 1);
      tokenRepository.save(verificationToken);
      throw new InvalidVerificationCodeException("Invalid or expired verification code");
    }

    applyPendingEmail(user, verificationToken);

    return userMapper.toDto(user);
  }

  /**
   * Copies the pending address onto the user, re-checking uniqueness (someone may have claimed it
   * between request and confirm). Shared by the code path above and the click-through link path in
   * AuthService.verifyEmail.
   */
  @Transactional
  public void applyPendingEmail(User user, EmailVerificationToken verificationToken) {
    String email = verificationToken.getPendingEmail();

    userRepository
        .findByEmail(email)
        .filter(other -> !other.getId().equals(user.getId()))
        .ifPresent(
            other -> {
              throw new UserAlreadyExistsException("This email is already in use");
            });

    user.setEmail(email);
    user.setEmailVerified(true);
    userRepository.save(user);

    verificationToken.setUsed(true);
    tokenRepository.save(verificationToken);
  }

  private String generate6DigitCode() {
    return String.format("%06d", RANDOM.nextInt(1_000_000));
  }
}
