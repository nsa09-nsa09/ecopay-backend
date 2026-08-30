package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import kz.hrms.splitupauth.dto.AuthResponse;
import kz.hrms.splitupauth.dto.LoginRequest;
import kz.hrms.splitupauth.dto.PasswordResetRequest;
import kz.hrms.splitupauth.dto.RegisterRequest;
import kz.hrms.splitupauth.dto.UserDto;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.EmailNotVerifiedException;
import kz.hrms.splitupauth.exception.UserAlreadyExistsException;
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
import org.springframework.security.crypto.password.PasswordEncoder;

/** Covers the regular account flow: email registration, email login, and reset guards. */
@ExtendWith(MockitoExtension.class)
class AuthServiceEmailAuthTest {

  private static final String EMAIL = "mail@test.kz";

  @Mock private UserRepository userRepository;
  @Mock private PasswordResetTokenRepository passwordResetTokenRepository;
  @Mock private EmailVerificationTokenRepository emailVerificationTokenRepository;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private JwtUtil jwtUtil;
  @Mock private RefreshTokenService refreshTokenService;
  @Mock private EmailService emailService;
  @Mock private RateLimitService rateLimitService;
  @Mock private UserMapper userMapper;
  @Mock private StaffTwoFactorService staffTwoFactorService;
  @Mock private LegalDocumentService legalDocumentService;
  @Mock private SlugService slugService;
  @Mock private EmailChangeService emailChangeService;

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

  @Test
  void registerByEmail_createsUserAndSendsEmailCode() {
    when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
    when(passwordEncoder.encode(anyString())).thenReturn("HASH");
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(emailVerificationTokenRepository.countByUserAndCreatedAtAfter(any(), any()))
        .thenReturn(0L);
    when(emailVerificationTokenRepository.findTopByUserOrderByCreatedAtDesc(any()))
        .thenReturn(Optional.empty());
    when(userMapper.toDto(any(User.class))).thenReturn(UserDto.builder().build());

    AuthResponse response = authService.register(registerRequest(" Mail@Test.KZ "), MailLocale.RU);

    ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(userCaptor.capture());
    User saved = userCaptor.getValue();
    assertEquals(EMAIL, saved.getEmail());
    assertNull(saved.getPhone(), "registration must not attach a profile phone");
    assertEquals(Boolean.FALSE, saved.getEmailVerified());

    verify(emailService)
        .sendVerificationEmail(eq(EMAIL), anyString(), anyString(), eq(MailLocale.RU));
    assertNull(response.getAccessToken());
    assertNull(response.getRefreshToken());
  }

  @Test
  void registerByEmail_rejectsDuplicateEmail() {
    when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

    assertThrows(
        UserAlreadyExistsException.class,
        () -> authService.register(registerRequest(EMAIL), MailLocale.RU));
    verify(userRepository, never()).save(any());
    verify(emailService, never())
        .sendVerificationEmail(anyString(), anyString(), anyString(), any(MailLocale.class));
  }

  @Test
  void loginByEmail_succeedsForVerifiedEmail() {
    User user = verifiedUser();
    when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("secret", user.getPassword())).thenReturn(true);
    when(staffTwoFactorService.requiresTwoFactor(user)).thenReturn(false);
    stubTokens(user);

    AuthResponse response = authService.login(loginRequest(" Mail@Test.KZ "));

    assertEquals("access", response.getAccessToken());
    verify(rateLimitService).checkLoginAttempts(EMAIL);
    verify(rateLimitService).recordLoginAttempt(EMAIL, true);
  }

  @Test
  void loginByEmail_unverifiedEmail_throwsEmailNotVerified() {
    User user = verifiedUser();
    user.setEmailVerified(false);
    when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("secret", user.getPassword())).thenReturn(true);

    assertThrows(EmailNotVerifiedException.class, () -> authService.login(loginRequest(EMAIL)));
    verify(jwtUtil, never()).generateAccessToken(anyString());
  }

  @Test
  void passwordReset_silentlySkipsUnverifiedEmail() {
    User user = User.builder().id(3L).email(EMAIL).emailVerified(false).build();
    when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

    PasswordResetRequest req = new PasswordResetRequest();
    req.setEmail(EMAIL);
    authService.requestPasswordReset(req);

    verify(passwordResetTokenRepository, never()).save(any());
    verify(emailService, never())
        .sendPasswordResetEmail(anyString(), anyString(), any(MailLocale.class));
  }

  @Test
  void passwordReset_sendsForVerifiedEmail() {
    User user = User.builder().id(3L).email(EMAIL).emailVerified(true).build();
    when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

    PasswordResetRequest req = new PasswordResetRequest();
    req.setEmail(EMAIL);
    authService.requestPasswordReset(req);

    verify(passwordResetTokenRepository).save(any());
    verify(emailService).sendPasswordResetEmail(eq(EMAIL), anyString(), any(MailLocale.class));
  }

  private static RegisterRequest registerRequest(String email) {
    RegisterRequest req = new RegisterRequest();
    req.setEmail(email);
    req.setPassword("Test1234");
    req.setDisplayName("Email User");
    req.setTermsAccepted(true);
    return req;
  }

  private static LoginRequest loginRequest(String email) {
    LoginRequest req = new LoginRequest();
    req.setEmail(email);
    req.setPassword("secret");
    return req;
  }

  private User verifiedUser() {
    return User.builder()
        .id(1L)
        .publicId("pubEmail12ab")
        .email(EMAIL)
        .phone("+77009998877")
        .password("ENC")
        .displayName("Email User")
        .role(Role.USER)
        .status(UserStatus.ACTIVE)
        .emailVerified(true)
        .build();
  }

  private void stubTokens(User user) {
    when(jwtUtil.generateAccessToken(user.getPublicId())).thenReturn("access");
    when(refreshTokenService.createRefreshToken(eq(user))).thenReturn("refresh");
    when(userMapper.toDto(user)).thenReturn(UserDto.builder().id(user.getId()).build());
  }
}
