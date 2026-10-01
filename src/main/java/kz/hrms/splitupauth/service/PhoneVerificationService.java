package kz.hrms.splitupauth.service;

import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;
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
  private final InMemoryRateLimiter rateLimiter;

  /**
   * Dev/test only: a master code that verifies any phone without the real SMS code (the dev SMS
   * provider only logs codes, so automated flows can't read them). Empty in prod → disabled.
   */
  @Value("${app.phone.dev-bypass-code:}")
  private String devBypassCode;

  /**
   * Issue a new verification code for the given phone and user. Enforces: - per-IP caps ({@link
   * SmsProperties#getMaxPerIpPerHour()} / {@link SmsProperties#getMaxPerIpPerDay()}) - resend
   * cooldown ({@link SmsProperties#getResendCooldownSeconds()}) - hourly cap ({@link
   * SmsProperties#getMaxAttemptsPerHour()}) - phone uniqueness against other users
   *
   * @param http the originating request, used for the per-IP cap; {@code null} skips it (internal
   *     callers and tests with no request context)
   */
  @Transactional
  public void requestCode(User user, String phone, HttpServletRequest http) {
    // Before any lookup: a number that belongs to someone else answers 409 below,
    // so without an IP cap that response is a free oracle for walking the number
    // space. Charging the quota here covers the probe as well as the send.
    enforceIpQuota(http);

    if (user.getPhoneVerifiedAt() != null && phone.equals(user.getPhone())) {
      // Already verified — no need to send again.
      return;
    }

    Optional<User> existingPhoneOwner = userRepository.findByPhone(phone);
    if (existingPhoneOwner.isPresent() && !existingPhoneOwner.get().getId().equals(user.getId())) {
      throw new PhoneAlreadyExistsException("Phone is already registered to another user");
    }

    LocalDateTime now = LocalDateTime.now();

    long attemptsLastHour =
        verificationRepository.countByPhoneAndCreatedAtAfter(phone, now.minusHours(1));
    if (attemptsLastHour >= smsProperties.getMaxAttemptsPerHour()) {
      throw new TooManySmsAttemptsException("Too many SMS code requests. Try again later.");
    }

    Optional<PhoneVerification> latest =
        verificationRepository.findTopByPhoneOrderByCreatedAtDesc(phone);
    if (latest.isPresent()) {
      LocalDateTime nextAllowed =
          latest.get().getCreatedAt().plusSeconds(smsProperties.getResendCooldownSeconds());
      if (now.isBefore(nextAllowed)) {
        throw new TooManySmsAttemptsException("Please wait before requesting another code.");
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
    PhoneVerification verification =
        PhoneVerification.builder()
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
   *
   * <p>{@code noRollbackFor} is load-bearing, not a style choice: the wrong-code branch increments
   * {@code attempts} and then throws, and a plain {@code @Transactional} rolls that increment back
   * with the exception. The counter then never leaves 0, {@link
   * SmsProperties#getMaxVerifyAttempts()} never trips, and the six-digit code can be guessed as
   * many times as its TTL allows.
   */
  @Transactional(noRollbackFor = InvalidVerificationCodeException.class)
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

    PhoneVerification verification =
        verificationRepository
            .findTopByUserAndPhoneAndVerifiedAtIsNullOrderByCreatedAtDesc(user, phone)
            .orElseThrow(
                () -> new ResourceNotFoundException("No active verification code for this phone"));

    if (verification.isExpired()) {
      throw new VerificationCodeExpiredException("Verification code expired");
    }

    if (verification.getAttempts() >= smsProperties.getMaxVerifyAttempts()) {
      throw new TooManySmsAttemptsException("Too many invalid attempts. Request a new code.");
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

  /**
   * Charges one code request against the caller's IP. Public so the silent registration-resend path
   * can charge it too — otherwise probing unknown numbers there costs an attacker nothing.
   *
   * <p>Sliding window in process memory (same limiter as feedback and visit pings). On a
   * multi-instance deployment each instance keeps its own counters, so the effective ceiling is the
   * configured cap times the instance count — move this to Redis before scaling out.
   */
  public void enforceIpQuota(HttpServletRequest http) {
    if (http == null) {
      return;
    }
    String ip = clientIp(http);
    if (smsProperties.getMaxPerIpPerHour() > 0) {
      rateLimiter.check(
          "sms:ip:h:" + ip,
          smsProperties.getMaxPerIpPerHour(),
          3600,
          "Too many code requests from this address. Try again later.");
    }
    if (smsProperties.getMaxPerIpPerDay() > 0) {
      rateLimiter.check(
          "sms:ip:d:" + ip,
          smsProperties.getMaxPerIpPerDay(),
          86_400,
          "Too many code requests from this address. Try again later.");
    }
  }

  /** Mirrors FeedbackService: trust the left-most X-Forwarded-For hop, else the socket address. */
  private String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      int comma = forwarded.indexOf(',');
      return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
    }
    return request.getRemoteAddr();
  }

  private String generate6DigitCode() {
    int n = RANDOM.nextInt(1_000_000);
    return String.format("%06d", n);
  }
}
