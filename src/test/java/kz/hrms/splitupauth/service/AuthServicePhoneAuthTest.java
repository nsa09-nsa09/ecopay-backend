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

import java.time.LocalDateTime;
import java.util.Optional;
import kz.hrms.splitupauth.dto.AuthResponse;
import kz.hrms.splitupauth.dto.LoginRequest;
import kz.hrms.splitupauth.dto.PasswordResetRequest;
import kz.hrms.splitupauth.dto.RegisterRequest;
import kz.hrms.splitupauth.dto.UserDto;
import kz.hrms.splitupauth.dto.VerifyPhoneCodeRequest;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.EmailNotVerifiedException;
import kz.hrms.splitupauth.exception.InvalidVerificationCodeException;
import kz.hrms.splitupauth.exception.PhoneNotVerifiedException;
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

/**
 * Covers phone-based registration (email is optional now): sign-up without an email, SMS-code
 * confirmation, phone login, and the password-reset guard for unverified emails.
 */
@ExtendWith(MockitoExtension.class)
class AuthServicePhoneAuthTest {

  private static final String PHONE = "+77001234567";

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
  @Mock private PhoneVerificationService phoneVerificationService;
  @Mock private EmailChangeService emailChangeService;

  /**
   * Real validator, not a mock: these tests assert on normalization (case-folding, trimming), so a
   * mock returning null would make them vacuous. The MX check is disabled so no test touches DNS.
   */
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

  // ===================== registration =====================

  @Test
  void registerByPhone_createsUserWithoutEmail_andSendsSmsCode() {
    when(userRepository.existsByPhone(PHONE)).thenReturn(false);
    when(passwordEncoder.encode("Test1234")).thenReturn("ENC");
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(userMapper.toDto(any(User.class))).thenReturn(UserDto.builder().build());

    AuthResponse response = authService.register(phoneRegisterRequest(), MailLocale.RU, null);

    ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(cap.capture());
    User saved = cap.getValue();
    assertNull(saved.getEmail(), "phone registration must not invent an email");
    assertEquals(PHONE, saved.getPhone());
    assertEquals(Boolean.FALSE, saved.getEmailVerified());

    verify(phoneVerificationService).requestCode(saved, PHONE, null);
    verify(emailService, never())
        .sendVerificationEmail(anyString(), anyString(), anyString(), any(MailLocale.class));

    // No tokens until the SMS code is confirmed.
    assertNull(response.getAccessToken());
    assertNull(response.getRefreshToken());
  }

  @Test
  void registerByPhone_rejectsDuplicatePhone() {
    when(userRepository.existsByPhone(PHONE)).thenReturn(true);

    assertThrows(
        UserAlreadyExistsException.class,
        () -> authService.register(phoneRegisterRequest(), MailLocale.RU, null));
    verify(userRepository, never()).save(any());
    verify(phoneVerificationService, never()).requestCode(any(), anyString(), any());
  }

  @Test
  void verifyPhoneCode_confirms_andIssuesTokens() {
    User user = phoneUser(null);
    when(userRepository.findByPhone(PHONE)).thenReturn(Optional.of(user));
    stubTokens(user);

    AuthResponse response = authService.verifyPhoneCode(verifyRequest("123456"));

    verify(phoneVerificationService).verifyCode(user, PHONE, "123456");
    assertEquals("access", response.getAccessToken());
    assertEquals("refresh", response.getRefreshToken());
  }

  @Test
  void verifyPhoneCode_isIdempotentForVerifiedPhone() {
    User user = phoneUser(LocalDateTime.now());
    when(userRepository.findByPhone(PHONE)).thenReturn(Optional.of(user));
    stubTokens(user);

    AuthResponse response = authService.verifyPhoneCode(verifyRequest("123456"));

    verify(phoneVerificationService, never()).verifyCode(any(), anyString(), anyString());
    assertEquals("access", response.getAccessToken());
  }

  @Test
  void verifyPhoneCode_unknownPhone_rejectedWithoutLeakingExistence() {
    when(userRepository.findByPhone(PHONE)).thenReturn(Optional.empty());

    assertThrows(
        InvalidVerificationCodeException.class,
        () -> authService.verifyPhoneCode(verifyRequest("123456")));
  }

  @Test
  void resendPhoneCode_staysSilentForUnknownAndVerifiedPhones() {
    when(userRepository.findByPhone(PHONE)).thenReturn(Optional.empty());
    authService.resendPhoneCode(PHONE, null);

    when(userRepository.findByPhone(PHONE)).thenReturn(Optional.of(phoneUser(LocalDateTime.now())));
    authService.resendPhoneCode(PHONE, null);

    verify(phoneVerificationService, never()).requestCode(any(), anyString(), any());
  }

  @Test
  void resendPhoneCode_reissuesForUnfinishedRegistration() {
    User user = phoneUser(null);
    when(userRepository.findByPhone(PHONE)).thenReturn(Optional.of(user));

    authService.resendPhoneCode(PHONE, null);

    verify(phoneVerificationService).requestCode(user, PHONE, null);
  }

  // ===================== login =====================

  @Test
  void loginByPhone_succeedsForVerifiedPhone() {
    User user = phoneUser(LocalDateTime.now());
    when(userRepository.findByPhone(PHONE)).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("secret", user.getPassword())).thenReturn(true);
    when(staffTwoFactorService.requiresTwoFactor(user)).thenReturn(false);
    stubTokens(user);

    AuthResponse response = authService.login(phoneLoginRequest());

    assertEquals("access", response.getAccessToken());
    verify(rateLimitService).recordLoginAttempt(PHONE, true);
  }

  @Test
  void loginByPhone_unverifiedPhone_throwsPhoneNotVerified() {
    User user = phoneUser(null);
    when(userRepository.findByPhone(PHONE)).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("secret", user.getPassword())).thenReturn(true);

    assertThrows(PhoneNotVerifiedException.class, () -> authService.login(phoneLoginRequest()));
    verify(jwtUtil, never()).generateAccessToken(anyString());
  }

  @Test
  void loginByEmail_unverifiedEmail_stillThrowsEmailNotVerified() {
    User user =
        User.builder()
            .id(2L)
            .publicId("pubEmail")
            .email("mail@test.kz")
            .password("ENC")
            .role(Role.USER)
            .status(UserStatus.ACTIVE)
            .emailVerified(false)
            .build();
    when(userRepository.findByEmail("mail@test.kz")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("secret", user.getPassword())).thenReturn(true);

    LoginRequest req = new LoginRequest();
    req.setEmail("mail@test.kz");
    req.setPassword("secret");

    assertThrows(EmailNotVerifiedException.class, () -> authService.login(req));
  }

  // ===================== password reset guard =====================

  @Test
  void passwordReset_silentlySkipsUnverifiedEmail() {
    User user = User.builder().id(3L).email("mail@test.kz").emailVerified(false).build();
    when(userRepository.findByEmail("mail@test.kz")).thenReturn(Optional.of(user));

    PasswordResetRequest req = new PasswordResetRequest();
    req.setEmail("mail@test.kz");
    authService.requestPasswordReset(req);

    verify(passwordResetTokenRepository, never()).save(any());
    verify(emailService, never())
        .sendPasswordResetEmail(anyString(), anyString(), any(MailLocale.class));
  }

  @Test
  void passwordReset_sendsForVerifiedEmail() {
    User user = User.builder().id(3L).email("mail@test.kz").emailVerified(true).build();
    when(userRepository.findByEmail("mail@test.kz")).thenReturn(Optional.of(user));

    PasswordResetRequest req = new PasswordResetRequest();
    req.setEmail("mail@test.kz");
    authService.requestPasswordReset(req);

    verify(passwordResetTokenRepository).save(any());
    verify(emailService)
        .sendPasswordResetEmail(eq("mail@test.kz"), anyString(), any(MailLocale.class));
  }

  // ===================== helpers =====================

  private static RegisterRequest phoneRegisterRequest() {
    RegisterRequest req = new RegisterRequest();
    req.setPhone(PHONE);
    req.setPassword("Test1234");
    req.setDisplayName("Phone User");
    req.setTermsAccepted(true);
    return req;
  }

  private static VerifyPhoneCodeRequest verifyRequest(String code) {
    VerifyPhoneCodeRequest req = new VerifyPhoneCodeRequest();
    req.setPhone(PHONE);
    req.setCode(code);
    return req;
  }

  private static LoginRequest phoneLoginRequest() {
    LoginRequest req = new LoginRequest();
    req.setPhone(PHONE);
    req.setPassword("secret");
    return req;
  }

  private User phoneUser(LocalDateTime phoneVerifiedAt) {
    return User.builder()
        .id(1L)
        .publicId("pubPhone12ab")
        .email(null)
        .phone(PHONE)
        .password("ENC")
        .displayName("Phone User")
        .role(Role.USER)
        .status(UserStatus.ACTIVE)
        .emailVerified(false)
        .phoneVerifiedAt(phoneVerifiedAt)
        .build();
  }

  private void stubTokens(User user) {
    when(jwtUtil.generateAccessToken(user.getPublicId())).thenReturn("access");
    when(refreshTokenService.createRefreshToken(eq(user))).thenReturn("refresh");
    when(userMapper.toDto(user)).thenReturn(UserDto.builder().id(user.getId()).build());
  }
}
