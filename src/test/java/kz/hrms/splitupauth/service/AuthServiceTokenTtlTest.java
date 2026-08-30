package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import kz.hrms.splitupauth.dto.PasswordResetRequest;
import kz.hrms.splitupauth.dto.ResendVerificationRequest;
import kz.hrms.splitupauth.entity.EmailVerificationToken;
import kz.hrms.splitupauth.entity.PasswordResetToken;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.repository.EmailVerificationTokenRepository;
import kz.hrms.splitupauth.repository.PasswordResetTokenRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Pins the lifetime of the two credentials this service mails out.
 *
 * <p>Both are bearer credentials that come to rest in a mailbox, and a mailbox outlives the moment
 * it was read: it syncs to an old phone, gets forwarded, sits open on a shared laptop. A long TTL
 * turns "someone reads your mail once" into "someone takes the account". Thirty minutes is enough
 * to open the message and type the code, and the user is never stranded — both flows can issue a
 * fresh credential on demand.
 *
 * <p>These assertions exist because the previous values (24 hours for confirmation, 1 hour for
 * reset) were easy to reintroduce by accident: nothing failed when they were wrong.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTokenTtlTest {

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
            emailChangeService,
            emailValidationService);
  }

  /**
   * Asserts the expiry lands on the expected minute mark. A one-minute band absorbs the clock
   * ticking between the service reading "now" and the assertion below without letting a genuinely
   * wrong unit (hours for minutes) slip through.
   */
  private static void assertExpiresInMinutes(LocalDateTime expiresAt, int expectedMinutes) {
    LocalDateTime now = LocalDateTime.now();
    Duration actual = Duration.between(now, expiresAt);
    assertTrue(
        actual.compareTo(Duration.ofMinutes(expectedMinutes - 1L)) > 0
            && actual.compareTo(Duration.ofMinutes(expectedMinutes + 1L)) < 0,
        "expected a TTL of about "
            + expectedMinutes
            + " minutes but the token lives for "
            + actual.toMinutes()
            + " minutes");
  }

  @Test
  void passwordResetTokenExpiresInThirtyMinutes() {
    User user =
        User.builder()
            .id(1L)
            .publicId("pub123456789")
            .email("known@test.kz")
            .emailVerified(true)
            .status(UserStatus.ACTIVE)
            .build();
    when(userRepository.findByEmail("known@test.kz")).thenReturn(Optional.of(user));

    PasswordResetRequest request = new PasswordResetRequest();
    request.setEmail("known@test.kz");
    authService.requestPasswordReset(request);

    ArgumentCaptor<PasswordResetToken> saved = ArgumentCaptor.forClass(PasswordResetToken.class);
    verify(passwordResetTokenRepository).save(saved.capture());
    assertExpiresInMinutes(saved.getValue().getExpiresAt(), AuthService.PASSWORD_RESET_TTL_MINUTES);
  }

  @Test
  void emailConfirmationTokenExpiresInThirtyMinutes() {
    User user =
        User.builder()
            .id(2L)
            .publicId("pub987654321")
            .email("known@test.kz")
            .emailVerified(false)
            .status(UserStatus.ACTIVE)
            .build();
    when(userRepository.findByEmail("known@test.kz")).thenReturn(Optional.of(user));
    when(emailVerificationTokenRepository.countByUserAndCreatedAtAfter(any(), any()))
        .thenReturn(0L);
    when(emailVerificationTokenRepository.findTopByUserOrderByCreatedAtDesc(any()))
        .thenReturn(Optional.empty());
    when(passwordEncoder.encode(anyString())).thenReturn("CODE_HASH");

    ResendVerificationRequest request = new ResendVerificationRequest();
    request.setEmail("known@test.kz");
    authService.resendVerificationEmail(request);

    ArgumentCaptor<EmailVerificationToken> saved =
        ArgumentCaptor.forClass(EmailVerificationToken.class);
    verify(emailVerificationTokenRepository).save(saved.capture());
    assertExpiresInMinutes(saved.getValue().getExpiresAt(), AuthService.CONFIRMATION_TTL_MINUTES);
  }

  /**
   * The TTL the code advertises in the email must be the TTL it actually has. These were out of
   * step before: the reset link claimed 30 minutes in the UI while the backend issued an hour.
   */
  @Test
  void advertisedTtlMatchesTheConfiguredOne() {
    assertTrue(
        AuthService.CONFIRMATION_TTL_MINUTES == 30 && AuthService.PASSWORD_RESET_TTL_MINUTES == 30,
        "email templates and the frontend both state 30 minutes; change them together");
  }
}
