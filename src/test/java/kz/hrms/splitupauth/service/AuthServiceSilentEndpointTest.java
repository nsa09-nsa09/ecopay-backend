package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import kz.hrms.splitupauth.dto.PasswordResetRequest;
import kz.hrms.splitupauth.dto.ResendVerificationRequest;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.MailDeliveryException;
import kz.hrms.splitupauth.repository.EmailVerificationTokenRepository;
import kz.hrms.splitupauth.repository.PasswordResetTokenRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Regression cover for an enumeration oracle found by running the app with SMTP switched off.
 *
 * <p>Both endpoints below answer 200 for an unknown address on purpose. Before this fix a
 * <em>known</em> address let {@link MailDeliveryException} escape as a 503, so while the mail
 * server was down the pair 200-vs-503 told an attacker exactly which addresses were registered —
 * across the entire user base, through an unauthenticated endpoint.
 *
 * <p>The rule these tests pin: on a deliberately-silent endpoint, our own infrastructure failing
 * must never change the answer the caller sees.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceSilentEndpointTest {

  @Mock UserRepository userRepository;
  @Mock PasswordResetTokenRepository passwordResetTokenRepository;
  @Mock EmailVerificationTokenRepository emailVerificationTokenRepository;
  @Mock PasswordEncoder passwordEncoder;
  @Mock JwtUtil jwtUtil;
  @Mock RefreshTokenService refreshTokenService;
  @Mock EmailService emailService;
  @Mock RateLimitService rateLimitService;
  @Mock UserMapper userMapper;
  @Mock StaffTwoFactorService staffTwoFactorService;
  @Mock LegalDocumentService legalDocumentService;
  @Mock SlugService slugService;
  @Mock PhoneVerificationService phoneVerificationService;
  @Mock EmailChangeService emailChangeService;

  private final EmailValidationService emailValidationService =
      new EmailValidationService(new EmailDomainService(false, 100, 1, 10));

  private AuthService authService;

  @BeforeEach
  void setUp() {
    authService =
        new AuthService(
            userRepository,
            passwordResetTokenRepository,
            emailVerificationTokenRepository,
            passwordEncoder,
            jwtUtil,
            refreshTokenService,
            emailService,
            rateLimitService,
            userMapper,
            staffTwoFactorService,
            legalDocumentService,
            slugService,
            phoneVerificationService,
            emailChangeService,
            emailValidationService);
  }

  private User verifiedUser() {
    return User.builder()
        .id(1L)
        .publicId("pub123456789")
        .email("known@test.kz")
        .emailVerified(true)
        .status(UserStatus.ACTIVE)
        .build();
  }

  private User unverifiedUser() {
    return User.builder()
        .id(2L)
        .publicId("pub987654321")
        .email("known@test.kz")
        .emailVerified(false)
        .status(UserStatus.ACTIVE)
        .build();
  }

  // ===================== password reset =====================

  @Test
  void passwordReset_staysSilentWhenSmtpIsDown() {
    User user = verifiedUser();
    when(userRepository.findByEmail("known@test.kz")).thenReturn(Optional.of(user));
    doThrow(new MailDeliveryException("Unable to send email right now"))
        .when(emailService)
        .sendPasswordResetEmail(anyString(), anyString(), any(MailLocale.class));

    PasswordResetRequest request = new PasswordResetRequest();
    request.setEmail("known@test.kz");

    // Must behave exactly like the unknown-address branch, which simply returns.
    assertDoesNotThrow(() -> authService.requestPasswordReset(request));
    // ...but the send must still have been attempted: swallowing the exception
    // is the fix, not skipping delivery.
    verify(emailService).sendPasswordResetEmail(anyString(), anyString(), any(MailLocale.class));
  }

  @Test
  void passwordReset_alreadySilentForUnknownAddress() {
    when(userRepository.findByEmail("stranger@test.kz")).thenReturn(Optional.empty());

    PasswordResetRequest request = new PasswordResetRequest();
    request.setEmail("stranger@test.kz");

    assertDoesNotThrow(() -> authService.requestPasswordReset(request));
  }

  // ===================== resend verification =====================

  @Test
  void resendVerification_staysSilentWhenSmtpIsDown() {
    User user = unverifiedUser();
    when(userRepository.findByEmail("known@test.kz")).thenReturn(Optional.of(user));
    when(emailVerificationTokenRepository.countByUserAndCreatedAtAfter(any(), any()))
        .thenReturn(0L);
    when(emailVerificationTokenRepository.findTopByUserOrderByCreatedAtDesc(any()))
        .thenReturn(Optional.empty());
    when(passwordEncoder.encode(anyString())).thenReturn("CODE_HASH");
    doThrow(new MailDeliveryException("Unable to send email right now"))
        .when(emailService)
        .sendVerificationEmail(anyString(), anyString(), anyString(), any(MailLocale.class));

    ResendVerificationRequest request = new ResendVerificationRequest();
    request.setEmail("known@test.kz");

    assertDoesNotThrow(() -> authService.resendVerificationEmail(request));
    verify(emailService)
        .sendVerificationEmail(anyString(), anyString(), anyString(), any(MailLocale.class));
  }

  @Test
  void resendVerification_staysSilentWhenRateLimited() {
    User user = unverifiedUser();
    when(userRepository.findByEmail("known@test.kz")).thenReturn(Optional.of(user));
    // Already at the hourly cap: the throttle must not surface as a 429 either.
    when(emailVerificationTokenRepository.countByUserAndCreatedAtAfter(any(), any()))
        .thenReturn((long) AuthService.MAX_SENDS_PER_HOUR);

    ResendVerificationRequest request = new ResendVerificationRequest();
    request.setEmail("known@test.kz");

    assertDoesNotThrow(() -> authService.resendVerificationEmail(request));
  }

  @Test
  void resendVerification_alreadySilentForUnknownAddress() {
    when(userRepository.findByEmail("stranger@test.kz")).thenReturn(Optional.empty());

    ResendVerificationRequest request = new ResendVerificationRequest();
    request.setEmail("stranger@test.kz");

    assertDoesNotThrow(() -> authService.resendVerificationEmail(request));
  }

  @Test
  void resendVerification_isSilentForAlreadyVerifiedAccount() {
    when(userRepository.findByEmail("known@test.kz")).thenReturn(Optional.of(verifiedUser()));

    ResendVerificationRequest request = new ResendVerificationRequest();
    request.setEmail("known@test.kz");

    assertDoesNotThrow(() -> authService.resendVerificationEmail(request));
    // Nothing to resend: a verified account must not trigger another email.
    verify(emailService, never())
        .sendVerificationEmail(anyString(), anyString(), anyString(), any(MailLocale.class));
  }
}
