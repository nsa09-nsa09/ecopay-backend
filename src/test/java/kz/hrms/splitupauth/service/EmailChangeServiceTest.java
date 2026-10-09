package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Covers adding/changing the account email from the profile: the address parks on the token's
 * pendingEmail and reaches users.email only after the one-time code is confirmed.
 */
@ExtendWith(MockitoExtension.class)
class EmailChangeServiceTest {

  @Mock UserRepository userRepository;
  @Mock EmailVerificationTokenRepository tokenRepository;
  @Mock PasswordEncoder passwordEncoder;
  @Mock EmailService emailService;
  @Mock UserMapper userMapper;

  /**
   * Real validator, not a mock: these tests assert on normalization (case-folding, trimming), so a
   * mock returning null would make them vacuous. The MX check is disabled so no test touches DNS.
   */
  @Spy
  EmailValidationService emailValidationService =
      new EmailValidationService(new EmailDomainService(false, 100, 1, 10));

  @InjectMocks EmailChangeService service;

  private User phoneOnlyUser() {
    return User.builder()
        .id(1L)
        .publicId("pubPhone12ab")
        .email(null)
        .phone("+77001234567")
        .emailVerified(false)
        .build();
  }

  // ===================== request =====================

  @Test
  void request_parksAddressOnToken_andEmailsCodeToNewAddress() {
    User user = phoneOnlyUser();
    when(userRepository.findByEmail("new@test.kz")).thenReturn(Optional.empty());
    when(tokenRepository.countByUserAndCreatedAtAfter(eq(user), any())).thenReturn(0L);
    when(tokenRepository.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.empty());
    when(passwordEncoder.encode(anyString())).thenReturn("CODE_HASH");

    service.requestChange(user, "New@Test.kz", MailLocale.RU);

    ArgumentCaptor<EmailVerificationToken> cap =
        ArgumentCaptor.forClass(EmailVerificationToken.class);
    verify(tokenRepository).save(cap.capture());
    EmailVerificationToken token = cap.getValue();
    assertEquals("new@test.kz", token.getPendingEmail(), "address must be normalized and parked");

    // The account's address is untouched until confirmation. requestChange may
    // still save the user to record the locale for the outgoing email, so
    // assert on the address itself rather than on "no save happened".
    assertEquals(null, user.getEmail());
    ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
    verify(userRepository, atMost(1)).save(savedUser.capture());
    savedUser
        .getAllValues()
        .forEach(u -> assertNull(u.getEmail(), "email must not be attached before confirmation"));

    verify(emailService)
        .sendEmailChangeConfirmation(
            eq("new@test.kz"), anyString(), anyString(), any(MailLocale.class));
  }

  @Test
  void request_rejectsEmailTakenByAnotherAccount() {
    User user = phoneOnlyUser();
    User other = User.builder().id(2L).email("new@test.kz").build();
    when(userRepository.findByEmail("new@test.kz")).thenReturn(Optional.of(other));

    assertThrows(
        UserAlreadyExistsException.class,
        () -> service.requestChange(user, "new@test.kz", MailLocale.RU));
    verify(emailService, never())
        .sendEmailChangeConfirmation(anyString(), anyString(), anyString(), any(MailLocale.class));
  }

  @Test
  void request_rejectsOwnAlreadyConfirmedEmail() {
    User user = phoneOnlyUser();
    user.setEmail("mine@test.kz");
    user.setEmailVerified(true);

    assertThrows(
        InvalidRequestException.class,
        () -> service.requestChange(user, "mine@test.kz", MailLocale.RU));
  }

  @Test
  void request_throttlesByCooldown() {
    User user = phoneOnlyUser();
    when(userRepository.findByEmail("new@test.kz")).thenReturn(Optional.empty());
    when(tokenRepository.countByUserAndCreatedAtAfter(eq(user), any())).thenReturn(1L);
    when(tokenRepository.findTopByUserOrderByCreatedAtDesc(user))
        .thenReturn(
            Optional.of(
                EmailVerificationToken.builder()
                    .createdAt(LocalDateTime.now().minusSeconds(10))
                    .expiresAt(LocalDateTime.now().plusMinutes(30))
                    .build()));

    assertThrows(
        TooManyRequestsException.class,
        () -> service.requestChange(user, "new@test.kz", MailLocale.RU));
    verify(emailService, never())
        .sendEmailChangeConfirmation(anyString(), anyString(), anyString(), any(MailLocale.class));
  }

  @Test
  void request_throttlesByHourlyCap() {
    User user = phoneOnlyUser();
    when(userRepository.findByEmail("new@test.kz")).thenReturn(Optional.empty());
    when(tokenRepository.countByUserAndCreatedAtAfter(eq(user), any())).thenReturn(5L);

    assertThrows(
        TooManyRequestsException.class,
        () -> service.requestChange(user, "new@test.kz", MailLocale.RU));
  }

  // ===================== confirm =====================

  private EmailVerificationToken pendingToken(User user) {
    return EmailVerificationToken.builder()
        .id(10L)
        .token("tok")
        .user(user)
        .pendingEmail("new@test.kz")
        .codeHash("CODE_HASH")
        .attempts(0)
        .createdAt(LocalDateTime.now())
        .expiresAt(LocalDateTime.now().plusMinutes(30))
        .used(false)
        .build();
  }

  @Test
  void confirm_attachesEmail_andMarksItVerified() {
    User user = phoneOnlyUser();
    EmailVerificationToken token = pendingToken(user);
    when(tokenRepository.findByUser(user)).thenReturn(Optional.of(token));
    when(passwordEncoder.matches("123456", "CODE_HASH")).thenReturn(true);
    when(userRepository.findByEmail("new@test.kz")).thenReturn(Optional.empty());
    when(userMapper.toDto(user)).thenReturn(UserDto.builder().email("new@test.kz").build());

    UserDto dto = service.confirmChange(user, "123456");

    assertEquals("new@test.kz", user.getEmail());
    assertEquals(Boolean.TRUE, user.getEmailVerified());
    assertEquals(Boolean.TRUE, token.getUsed());
    assertEquals("new@test.kz", dto.getEmail());
    verify(userRepository).save(user);
  }

  @Test
  void confirm_wrongCode_countsAttempt() {
    User user = phoneOnlyUser();
    EmailVerificationToken token = pendingToken(user);
    when(tokenRepository.findByUser(user)).thenReturn(Optional.of(token));
    when(passwordEncoder.matches("999999", "CODE_HASH")).thenReturn(false);

    assertThrows(
        InvalidVerificationCodeException.class, () -> service.confirmChange(user, "999999"));

    assertEquals(1, token.getAttempts());
    assertEquals(null, user.getEmail());
  }

  @Test
  void confirm_expiredCode_rejected() {
    User user = phoneOnlyUser();
    EmailVerificationToken token = pendingToken(user);
    token.setExpiresAt(LocalDateTime.now().minusMinutes(1));
    when(tokenRepository.findByUser(user)).thenReturn(Optional.of(token));

    assertThrows(
        VerificationCodeExpiredException.class, () -> service.confirmChange(user, "123456"));
  }

  @Test
  void confirm_tooManyAttempts_rejected() {
    User user = phoneOnlyUser();
    EmailVerificationToken token = pendingToken(user);
    token.setAttempts(5);
    when(tokenRepository.findByUser(user)).thenReturn(Optional.of(token));

    assertThrows(
        InvalidVerificationCodeException.class, () -> service.confirmChange(user, "123456"));
  }

  @Test
  void confirm_rejectsAddressClaimedBetweenRequestAndConfirm() {
    User user = phoneOnlyUser();
    EmailVerificationToken token = pendingToken(user);
    when(tokenRepository.findByUser(user)).thenReturn(Optional.of(token));
    when(passwordEncoder.matches("123456", "CODE_HASH")).thenReturn(true);
    User claimer = User.builder().id(9L).email("new@test.kz").build();
    when(userRepository.findByEmail("new@test.kz")).thenReturn(Optional.of(claimer));

    assertThrows(UserAlreadyExistsException.class, () -> service.confirmChange(user, "123456"));
    assertEquals(null, user.getEmail());
  }

  @Test
  void confirm_withoutPendingRequest_rejected() {
    User user = phoneOnlyUser();
    when(tokenRepository.findByUser(user)).thenReturn(Optional.empty());

    assertThrows(
        InvalidVerificationCodeException.class, () -> service.confirmChange(user, "123456"));
  }
}
