package kz.hrms.splitupauth.service;

import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.UUID;
import kz.hrms.splitupauth.dto.*;
import kz.hrms.splitupauth.entity.EmailVerificationToken;
import kz.hrms.splitupauth.entity.LegalDocument;
import kz.hrms.splitupauth.entity.PasswordResetToken;
import kz.hrms.splitupauth.entity.RefreshToken;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.StaffTwoFactorChallenge;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.*;
import kz.hrms.splitupauth.repository.EmailVerificationTokenRepository;
import kz.hrms.splitupauth.repository.PasswordResetTokenRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

  private static final SecureRandom RANDOM = new SecureRandom();

  /** Wrong-code attempts allowed before the confirmation must be restarted (resent). */
  private static final int MAX_CODE_ATTEMPTS = 5;

  /** Minimum seconds between two verification emails for the same account. */
  static final int RESEND_COOLDOWN_SECONDS = 60;

  /** Verification emails allowed per account per hour (anti-mailbomb). */
  static final int MAX_SENDS_PER_HOUR = 5;

  /**
   * Lifetime of a one-time confirmation token, shared by the emailed link and the 6-digit code.
   *
   * <p>Short on purpose: the token is a bearer credential sitting in a mailbox, and a mailbox is
   * exactly what gets forwarded, synced to a phone that later changes hands, or read by whoever
   * borrows an unlocked laptop. Thirty minutes covers "open the mail, type the code" and little
   * else. The user is never stuck — /auth/resend-verification issues a fresh one.
   */
  public static final int CONFIRMATION_TTL_MINUTES = 30;

  /** Lifetime of a password-reset link. Same reasoning, and the stakes are higher. */
  public static final int PASSWORD_RESET_TTL_MINUTES = 30;

  private final UserRepository userRepository;
  private final PasswordResetTokenRepository passwordResetTokenRepository;
  private final EmailVerificationTokenRepository emailVerificationTokenRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtUtil jwtUtil;
  private final RefreshTokenService refreshTokenService;
  private final EmailService emailService;
  private final RateLimitService rateLimitService;
  private final UserMapper userMapper;
  private final StaffTwoFactorService staffTwoFactorService;
  private final LegalDocumentService legalDocumentService;
  private final SlugService slugService;
  private final PhoneVerificationService phoneVerificationService;
  private final EmailChangeService emailChangeService;
  private final EmailValidationService emailValidationService;

  // Dev/test only: auto-verify email on registration so login works without SMTP.
  @Value("${app.dev.auto-verify-email:false}")
  private boolean devAutoVerifyEmail;

  @Transactional
  public AuthResponse register(
      RegisterRequest request, MailLocale locale, HttpServletRequest http) {
    boolean byPhone = request.getPhone() != null && !request.getPhone().isBlank();

    // Email registration attaches a brand-new address, so it gets the full
    // pipeline: canonicalise, then reject malformed addresses and domains that
    // publish no MX. Skipping this is how gmial.com rows end up in the table.
    String email =
        byPhone ? null : emailValidationService.normalizeAndValidateDeliverable(request.getEmail());

    if (byPhone) {
      if (userRepository.existsByPhone(request.getPhone())) {
        throw new UserAlreadyExistsException("User with this phone already exists");
      }
    } else if (userRepository.existsByEmail(email)) {
      throw new UserAlreadyExistsException("User with this email already exists");
    }

    // @AssertTrue on RegisterRequest.termsAccepted already rejects null/false
    // with a 400, so anything reaching this point has consented. Persist the
    // acceptance timestamp + document versions. If the caller didn't send
    // versions, fall back to the current server-side versions.
    Integer termsVersion =
        request.getAcceptedTermsVersion() != null
            ? request.getAcceptedTermsVersion()
            : legalDocumentService.currentVersion(LegalDocument.DocType.TERMS);
    Integer privacyVersion =
        request.getAcceptedPrivacyVersion() != null
            ? request.getAcceptedPrivacyVersion()
            : legalDocumentService.currentVersion(LegalDocument.DocType.PRIVACY);

    // Email is optional: phone-registered accounts have none until the user
    // adds one in the profile (email is only ever attached after the emailed
    // code is confirmed).
    User user =
        User.builder()
            .email(email)
            .phone(byPhone ? request.getPhone() : null)
            .locale(locale.tag())
            .password(passwordEncoder.encode(request.getPassword()))
            .displayName(request.getDisplayName())
            .status(UserStatus.ACTIVE)
            .role(Role.USER)
            .reputation(User.DEFAULT_REPUTATION)
            .emailVerified(false)
            .termsAcceptedAt(LocalDateTime.now())
            .acceptedTermsVersion(termsVersion)
            .acceptedPrivacyVersion(privacyVersion)
            .build();

    // Assign a unique human-readable slug before the row is persisted so the
    // returned DTO already carries it.
    slugService.assignInitialSlug(user);
    user = userRepository.save(user);

    if (byPhone) {
      // SMS the 6-digit confirmation code. Cooldowns, hourly caps and code TTL
      // are enforced by PhoneVerificationService — the same protections as the
      // profile phone-verification flow.
      phoneVerificationService.requestCode(user, request.getPhone(), http);

      // No tokens: the account must confirm the SMS code first (see
      // verifyPhoneCode), mirroring the email registration contract.
      return AuthResponse.builder().user(userMapper.toDto(user)).build();
    }

    if (devAutoVerifyEmail) {
      // Dev/test: skip the email round-trip so the account can log in without SMTP.
      // Tokens are issued straight away so the frontend can drop the user in.
      user.setEmailVerified(true);
      user = userRepository.save(user);
      return issueTokens(user);
    }

    sendVerificationEmail(user);

    // No tokens issued: the account must confirm the emailed code (or link)
    // before it is considered fully registered and allowed to log in.
    return AuthResponse.builder().user(userMapper.toDto(user)).build();
  }

  /**
   * Final step of phone registration: confirm the SMS code. On success the phone is marked verified
   * and the user is logged in straight away (tokens issued), mirroring verifyEmailCode.
   */
  @Transactional
  public AuthResponse verifyPhoneCode(VerifyPhoneCodeRequest request) {
    User user =
        userRepository
            .findByPhone(request.getPhone())
            .orElseThrow(
                () -> new InvalidVerificationCodeException("Invalid or expired verification code"));

    // Already verified — the previous attempt went through. Be idempotent and
    // just hand back a fresh session rather than erroring the user out.
    if (user.getPhoneVerifiedAt() != null) {
      return issueTokens(user);
    }

    phoneVerificationService.verifyCode(user, request.getPhone(), request.getCode());

    return issueTokens(user);
  }

  /**
   * Re-issue the SMS code for an unfinished phone registration. Stays silent for unknown or
   * already-verified phones to avoid phone-number enumeration; cooldown and hourly caps are
   * enforced by PhoneVerificationService.
   */
  @Transactional
  public void resendPhoneCode(String phone, HttpServletRequest http) {
    User user = userRepository.findByPhone(phone).orElse(null);
    if (user == null || user.getPhoneVerifiedAt() != null) {
      // Charge the IP quota even though nothing is sent: this endpoint answers 200
      // for unknown numbers on purpose, so a free silent path here would just move
      // the enumeration oracle rather than close it.
      phoneVerificationService.enforceIpQuota(http);
      return;
    }
    phoneVerificationService.requestCode(user, phone, http);
  }

  @Transactional
  public AuthResponse login(LoginRequest request) {
    boolean byPhone = request.getPhone() != null && !request.getPhone().isBlank();

    // Format check only (level 1). The address already exists in the database
    // here, so an MX lookup would add latency without adding safety — and would
    // lock out an existing user whose provider's DNS is having a bad day.
    // Normalizing is not optional: without it "User@Gmail.com" misses the row
    // stored as "user@gmail.com" and reads as wrong credentials.
    String email =
        byPhone ? null : emailValidationService.normalizeAndValidateFormat(request.getEmail());

    // Rate-limit on the canonical identifier so case variants share one bucket
    // rather than giving an attacker a fresh allowance per spelling.
    String identifier = byPhone ? request.getPhone() : email;
    rateLimitService.checkLoginAttempts(identifier);

    User user =
        (byPhone
                ? userRepository.findByPhone(request.getPhone())
                : userRepository.findByEmail(email))
            .orElseThrow(
                () -> {
                  rateLimitService.recordLoginAttempt(identifier, false);
                  return new InvalidCredentialsException("Invalid credentials");
                });

    if (user.getStatus() == UserStatus.BANNED) {
      throw new UserBannedException(
          "Your account has been banned", user.getBanReason(), user.getBannedAt());
    }

    if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
      rateLimitService.recordLoginAttempt(identifier, false);
      throw new InvalidCredentialsException("Invalid credentials");
    }

    // The account counts as confirmed when either channel it registered with
    // is verified: a confirmed email, or a confirmed phone (phone-registered
    // accounts have no email at all until one is added in the profile).
    boolean emailConfirmed =
        user.getEmail() != null && Boolean.TRUE.equals(user.getEmailVerified());
    boolean phoneConfirmed = user.getPhoneVerifiedAt() != null;
    if (!emailConfirmed && !phoneConfirmed) {
      if (user.getEmail() == null) {
        throw new PhoneNotVerifiedException(
            "Phone not verified. Please enter the code we sent you by SMS.");
      }
      throw new EmailNotVerifiedException(
          "Email not verified. Please check your inbox for the verification link.");
    }

    rateLimitService.recordLoginAttempt(identifier, true);

    // ADMIN / SUPPORT accounts must complete an email 2FA step before any
    // access or refresh tokens are issued.
    if (staffTwoFactorService.requiresTwoFactor(user)) {
      StaffTwoFactorChallenge challenge = staffTwoFactorService.createChallenge(user);
      return AuthResponse.builder()
          .requiresTwoFactor(true)
          .challengeId(challenge.getId())
          .expiresAt(challenge.getExpiresAt())
          .maskedEmail(StaffTwoFactorService.maskEmail(user.getEmail()))
          .build();
    }

    return issueTokens(user);
  }

  /**
   * Second step of the staff login flow. Verifies the OTP and, on success, returns the normal
   * access + refresh token bundle.
   */
  @Transactional
  public AuthResponse verifyStaffTwoFactor(TwoFactorVerifyRequest request) {
    User user = staffTwoFactorService.verifyChallenge(request.getChallengeId(), request.getCode());

    if (user.getStatus() == UserStatus.BANNED) {
      throw new UserBannedException(
          "Your account has been banned", user.getBanReason(), user.getBannedAt());
    }

    return issueTokens(user);
  }

  /**
   * Re-issue the OTP for an existing staff 2FA challenge. Cooldown-protected by the underlying
   * service.
   */
  @Transactional
  public void resendStaffTwoFactor(TwoFactorResendRequest request) {
    staffTwoFactorService.resendChallenge(request.getChallengeId());
  }

  private AuthResponse issueTokens(User user) {
    // Subject is the immutable publicId, not the email: email is optional now
    // and may change. JwtAuthenticationFilter still resolves legacy
    // email-subject tokens for sessions issued before this switch.
    String accessToken = jwtUtil.generateAccessToken(user.getPublicId());
    String refreshToken = refreshTokenService.createRefreshToken(user);

    // Track the moment tokens were last issued so the admin panel can show
    // "online presence". Refresh-token rotation does NOT touch this field —
    // refreshToken() does not call issueTokens, only authenticated sessions.
    user.setLastLoginAt(LocalDateTime.now());
    userRepository.save(user);

    return AuthResponse.builder()
        .accessToken(accessToken)
        .refreshToken(refreshToken)
        .user(userMapper.toDto(user))
        .build();
  }

  @Transactional
  public AuthResponse refreshToken(RefreshTokenRequest request) {
    RefreshToken refreshToken = refreshTokenService.validateRefreshToken(request.getRefreshToken());
    User user = refreshToken.getUser();

    if (user.getStatus() == UserStatus.BANNED) {
      throw new UserBannedException(
          "Your account has been banned", user.getBanReason(), user.getBannedAt());
    }

    // Rotate: revoke the presented token first, then issue a fresh one.
    refreshTokenService.revokeRefreshToken(request.getRefreshToken());

    String accessToken = jwtUtil.generateAccessToken(user.getPublicId());
    String newRefreshToken = refreshTokenService.createRefreshToken(user);

    return AuthResponse.builder()
        .accessToken(accessToken)
        .refreshToken(newRefreshToken)
        .user(userMapper.toDto(user))
        .build();
  }

  @Transactional
  public void logout(String refreshTokenValue) {
    refreshTokenService.revokeRefreshToken(refreshTokenValue);
  }

  @Transactional
  public void requestPasswordReset(PasswordResetRequest request) {
    // Normalize only — never validate loudly here. Any thrown error (bad
    // format, dead domain) would be a different response than the silent
    // success below, handing an attacker an oracle for which addresses exist.
    String email = emailValidationService.normalize(request.getEmail());
    User user = email == null ? null : userRepository.findByEmail(email).orElse(null);

    // Silent for unknown addresses AND for accounts whose email was never
    // confirmed: password recovery only works through a verified email, and
    // responding differently would leak which addresses exist in the system.
    if (user == null || !Boolean.TRUE.equals(user.getEmailVerified())) {
      return;
    }

    passwordResetTokenRepository.deleteByUser(user);

    String token = UUID.randomUUID().toString();
    PasswordResetToken resetToken =
        PasswordResetToken.builder()
            .token(token)
            .user(user)
            .expiresAt(LocalDateTime.now().plusMinutes(PASSWORD_RESET_TTL_MINUTES))
            .used(false)
            .build();

    passwordResetTokenRepository.save(resetToken);

    try {
      emailService.sendPasswordResetEmail(
          user.getEmail(), token, MailLocale.from(user.getLocale()));
    } catch (MailDeliveryException e) {
      // This endpoint answers identically for unknown and unverified addresses
      // (see above), so it must answer identically when our mail server is
      // down too — otherwise 200-vs-503 tells an attacker exactly which
      // addresses are registered and verified. The token stays valid; the user
      // can request another one once delivery recovers.
      log.error("Password-reset email failed for account id={}: {}", user.getId(), e.getMessage());
    }
  }

  @Transactional
  public void confirmPasswordReset(PasswordResetConfirmRequest request) {

    PasswordResetToken resetToken =
        passwordResetTokenRepository
            .findByToken(request.getToken())
            .orElseThrow(() -> new TokenExpiredException("Invalid or expired reset token"));

    if (resetToken.getUsed()) {
      throw new TokenExpiredException("Reset token already used");
    }

    if (resetToken.isExpired()) {
      throw new TokenExpiredException("Reset token expired");
    }

    User user = resetToken.getUser();

    user.setPassword(passwordEncoder.encode(request.getNewPassword()));
    userRepository.save(user);

    resetToken.setUsed(true);
    passwordResetTokenRepository.save(resetToken);

    refreshTokenService.revokeAllUserTokens(user);
  }

  @Transactional
  public void verifyEmail(String token) {
    EmailVerificationToken verificationToken =
        emailVerificationTokenRepository
            .findByToken(token)
            .orElseThrow(() -> new TokenExpiredException("Invalid or expired verification token"));

    if (verificationToken.getUsed()) {
      throw new TokenExpiredException("Verification token already used");
    }

    if (verificationToken.isExpired()) {
      throw new TokenExpiredException("Verification token expired");
    }

    User user = verificationToken.getUser();

    // Click-through link of the profile add/change-email flow: the address is
    // parked on the token and only attached to the account now.
    if (verificationToken.getPendingEmail() != null) {
      emailChangeService.applyPendingEmail(user, verificationToken);
      return;
    }

    user.setEmailVerified(true);
    userRepository.save(user);

    verificationToken.setUsed(true);
    emailVerificationTokenRepository.save(verificationToken);
  }

  @Transactional
  public void resendVerificationEmail(ResendVerificationRequest request) {
    // Silent path: normalize but never throw, same enumeration argument as
    // requestPasswordReset.
    String email = emailValidationService.normalize(request.getEmail());
    User user = email == null ? null : userRepository.findByEmail(email).orElse(null);

    // Stay silent for unknown or already-verified accounts to avoid email enumeration.
    if (user == null || Boolean.TRUE.equals(user.getEmailVerified())) {
      return;
    }

    try {
      sendVerificationEmail(user);
    } catch (TooManyRequestsException e) {
      // This endpoint is unauthenticated and takes an arbitrary address, so a
      // 429 here would answer "yes, that address exists and is unverified" —
      // exactly the enumeration signal the silent branches above avoid. Drop
      // the send instead; the client shows its own resend countdown.
      log.debug("Suppressed resend for rate-limited account id={}", user.getId());
    } catch (MailDeliveryException e) {
      // Same reasoning, and it bites harder: while SMTP is down an unknown
      // address returns 200 and a real one would return 503, handing an
      // attacker a clean oracle over the whole user base. Our outage must not
      // become a disclosure, so answer identically and page the operator
      // through the logs instead.
      log.error("Verification resend failed for account id={}: {}", user.getId(), e.getMessage());
    }
  }

  /**
   * Confirm the 6-digit code emailed at registration. On success the account is marked verified and
   * logged in straight away (tokens issued), matching the staff-2FA "verify then enter" flow.
   */
  @Transactional
  public AuthResponse verifyEmailCode(VerifyEmailCodeRequest request) {
    String email = emailValidationService.normalize(request.getEmail());
    User user =
        userRepository
            .findByEmail(email)
            .orElseThrow(
                () -> new InvalidVerificationCodeException("Invalid or expired verification code"));

    // Already verified — the previous attempt went through. Be idempotent and
    // just hand back a fresh session rather than erroring the user out.
    if (Boolean.TRUE.equals(user.getEmailVerified())) {
      return issueTokens(user);
    }

    EmailVerificationToken verificationToken =
        emailVerificationTokenRepository
            .findByUser(user)
            .orElseThrow(
                () -> new InvalidVerificationCodeException("Invalid or expired verification code"));

    if (verificationToken.getUsed() || verificationToken.getCodeHash() == null) {
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

    if (!passwordEncoder.matches(request.getCode(), verificationToken.getCodeHash())) {
      verificationToken.setAttempts(verificationToken.getAttempts() + 1);
      emailVerificationTokenRepository.save(verificationToken);
      throw new InvalidVerificationCodeException("Invalid or expired verification code");
    }

    user.setEmailVerified(true);
    userRepository.save(user);

    verificationToken.setUsed(true);
    emailVerificationTokenRepository.save(verificationToken);

    return issueTokens(user);
  }

  /**
   * Issues a fresh verification token + code and emails it.
   *
   * <p>Rate-limited on the same terms as the profile email-change flow: an unauthenticated caller
   * can hit /auth/resend-verification with any address, so without a cooldown this endpoint is a
   * free way to mailbomb a third party and burn our sending reputation.
   */
  private void sendVerificationEmail(User user) {
    LocalDateTime now = LocalDateTime.now();

    long sendsLastHour =
        emailVerificationTokenRepository.countByUserAndCreatedAtAfter(user, now.minusHours(1));
    if (sendsLastHour >= MAX_SENDS_PER_HOUR) {
      throw new TooManyRequestsException("Too many confirmation emails. Try again later.");
    }

    emailVerificationTokenRepository
        .findTopByUserOrderByCreatedAtDesc(user)
        .filter(t -> t.getCreatedAt().isAfter(now.minusSeconds(RESEND_COOLDOWN_SECONDS)))
        .ifPresent(
            t -> {
              throw new TooManyRequestsException("Please wait before requesting another code.");
            });

    emailVerificationTokenRepository.deleteByUser(user);

    String token = UUID.randomUUID().toString();
    String code = generate6DigitCode();
    EmailVerificationToken verificationToken =
        EmailVerificationToken.builder()
            .token(token)
            .user(user)
            .codeHash(passwordEncoder.encode(code))
            .attempts(0)
            .expiresAt(now.plusMinutes(CONFIRMATION_TTL_MINUTES))
            .used(false)
            .build();

    emailVerificationTokenRepository.save(verificationToken);

    emailService.sendVerificationEmail(
        user.getEmail(), token, code, MailLocale.from(user.getLocale()));
  }

  private String generate6DigitCode() {
    return String.format("%06d", RANDOM.nextInt(1_000_000));
  }
}
