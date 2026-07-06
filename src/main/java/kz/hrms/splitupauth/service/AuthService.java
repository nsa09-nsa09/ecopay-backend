package kz.hrms.splitupauth.service;

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

  // Dev/test only: auto-verify email on registration so login works without SMTP.
  @Value("${app.dev.auto-verify-email:false}")
  private boolean devAutoVerifyEmail;

  @Transactional
  public AuthResponse register(RegisterRequest request) {
    if (userRepository.existsByEmail(request.getEmail())) {
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

    // Phone is no longer collected at registration — it's requested at
    // room-creation time (when the platform actually needs to verify it).
    User user =
        User.builder()
            .email(request.getEmail())
            .password(passwordEncoder.encode(request.getPassword()))
            .displayName(request.getDisplayName())
            .status(UserStatus.ACTIVE)
            .role(Role.USER)
            .reputation(0)
            .emailVerified(false)
            .termsAcceptedAt(LocalDateTime.now())
            .acceptedTermsVersion(termsVersion)
            .acceptedPrivacyVersion(privacyVersion)
            .build();

    user = userRepository.save(user);

    if (devAutoVerifyEmail) {
      // Dev/test: skip the email round-trip so the account can log in without SMTP.
      user.setEmailVerified(true);
      user = userRepository.save(user);
    } else {
      sendVerificationEmail(user);
    }

    // No tokens issued: the account must verify its email before logging in.
    return AuthResponse.builder().user(userMapper.toDto(user)).build();
  }

  @Transactional
  public AuthResponse login(LoginRequest request) {
    rateLimitService.checkLoginAttempts(request.getEmail());

    User user =
        userRepository
            .findByEmail(request.getEmail())
            .orElseThrow(
                () -> {
                  rateLimitService.recordLoginAttempt(request.getEmail(), false);
                  return new InvalidCredentialsException("Invalid email or password");
                });

    if (user.getStatus() == UserStatus.BANNED) {
      throw new UserBannedException(
          "Your account has been banned", user.getBanReason(), user.getBannedAt());
    }

    if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
      rateLimitService.recordLoginAttempt(request.getEmail(), false);
      throw new InvalidCredentialsException("Invalid email or password");
    }

    if (Boolean.FALSE.equals(user.getEmailVerified())) {
      throw new EmailNotVerifiedException(
          "Email not verified. Please check your inbox for the verification link.");
    }

    rateLimitService.recordLoginAttempt(request.getEmail(), true);

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
    String accessToken = jwtUtil.generateAccessToken(user.getEmail());
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

    String accessToken = jwtUtil.generateAccessToken(user.getEmail());
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
    User user = userRepository.findByEmail(request.getEmail()).orElse(null);

    if (user == null) {
      return;
    }

    passwordResetTokenRepository.deleteByUser(user);

    String token = UUID.randomUUID().toString();
    PasswordResetToken resetToken =
        PasswordResetToken.builder()
            .token(token)
            .user(user)
            .expiresAt(LocalDateTime.now().plusHours(1))
            .used(false)
            .build();

    passwordResetTokenRepository.save(resetToken);

    emailService.sendPasswordResetEmail(user.getEmail(), token);
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
    user.setEmailVerified(true);
    userRepository.save(user);

    verificationToken.setUsed(true);
    emailVerificationTokenRepository.save(verificationToken);
  }

  @Transactional
  public void resendVerificationEmail(ResendVerificationRequest request) {
    User user = userRepository.findByEmail(request.getEmail()).orElse(null);

    // Stay silent for unknown or already-verified accounts to avoid email enumeration.
    if (user == null || Boolean.TRUE.equals(user.getEmailVerified())) {
      return;
    }

    sendVerificationEmail(user);
  }

  private void sendVerificationEmail(User user) {
    emailVerificationTokenRepository.deleteByUser(user);

    String token = UUID.randomUUID().toString();
    EmailVerificationToken verificationToken =
        EmailVerificationToken.builder()
            .token(token)
            .user(user)
            .expiresAt(LocalDateTime.now().plusHours(24))
            .used(false)
            .build();

    emailVerificationTokenRepository.save(verificationToken);

    emailService.sendVerificationEmail(user.getEmail(), token);
  }
}
