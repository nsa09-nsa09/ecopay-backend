package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.dto.AuthResponse;
import kz.hrms.splitupauth.dto.LoginRequest;
import kz.hrms.splitupauth.dto.RegisterRequest;
import kz.hrms.splitupauth.dto.VerifyPhoneCodeRequest;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.PhoneNotVerifiedException;
import kz.hrms.splitupauth.exception.UserAlreadyExistsException;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Phone registration end-to-end against real Postgres (V52: users.email is nullable): sign-up
 * without email, SMS-code confirmation via the dev bypass code, phone login, and the publicId JWT
 * subject.
 */
class PhoneRegistrationIntegrationTest extends AbstractIntegrationTest {

  @Autowired AuthService authService;
  @Autowired UserRepository userRepository;
  @Autowired JwtUtil jwtUtil;

  private static final AtomicInteger SEQ = new AtomicInteger();

  private static String uniquePhone() {
    // +77 9xx xx xx xx range, unique per test run.
    return "+779"
        + String.format("%08d", SEQ.incrementAndGet() * 137 + (int) (System.nanoTime() % 100000));
  }

  private static RegisterRequest phoneRegister(String phone) {
    RegisterRequest req = new RegisterRequest();
    req.setPhone(phone);
    req.setPassword("Test1234");
    req.setDisplayName("Phone User");
    req.setTermsAccepted(true);
    return req;
  }

  @Test
  void registerByPhone_persistsUserWithNullEmail_andIssuesNoTokensYet() {
    String phone = uniquePhone();

    AuthResponse response = authService.register(phoneRegister(phone), MailLocale.RU, null);

    assertNull(response.getAccessToken(), "no session until the SMS code is confirmed");
    User saved = userRepository.findByPhone(phone).orElseThrow();
    assertNull(saved.getEmail(), "email column must accept NULL after V52");
    assertNull(saved.getPhoneVerifiedAt());
    assertNotNull(saved.getPublicId());
    assertNotNull(saved.getSlug());
  }

  @Test
  void fullPhoneFlow_register_confirmSms_login() {
    String phone = uniquePhone();
    authService.register(phoneRegister(phone), MailLocale.RU, null);

    // Unverified phone cannot log in yet.
    LoginRequest login = new LoginRequest();
    login.setPhone(phone);
    login.setPassword("Test1234");
    assertThrows(PhoneNotVerifiedException.class, () -> authService.login(login));

    // Confirm with the dev bypass code (test env has app.phone.dev-bypass-code=000000).
    VerifyPhoneCodeRequest verify = new VerifyPhoneCodeRequest();
    verify.setPhone(phone);
    verify.setCode("000000");
    AuthResponse confirmed = authService.verifyPhoneCode(verify);

    assertNotNull(confirmed.getAccessToken());
    assertNotNull(confirmed.getRefreshToken());

    // The JWT subject is the immutable publicId, not an email.
    User user = userRepository.findByPhone(phone).orElseThrow();
    assertEquals(user.getPublicId(), jwtUtil.extractUsername(confirmed.getAccessToken()));
    assertNotNull(user.getPhoneVerifiedAt());

    // And a normal phone + password login now works.
    AuthResponse loggedIn = authService.login(login);
    assertNotNull(loggedIn.getAccessToken());
  }

  @Test
  void registerByPhone_duplicatePhone_rejected() {
    String phone = uniquePhone();
    authService.register(phoneRegister(phone), MailLocale.RU, null);

    assertThrows(
        UserAlreadyExistsException.class,
        () -> authService.register(phoneRegister(phone), MailLocale.RU, null));
  }
}
