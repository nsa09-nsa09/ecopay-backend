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
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.UserAlreadyExistsException;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Email registration/login end-to-end against real Postgres and Flyway migrations. */
class EmailRegistrationIntegrationTest extends AbstractIntegrationTest {

  @Autowired AuthService authService;
  @Autowired UserRepository userRepository;
  @Autowired JwtUtil jwtUtil;

  private static final AtomicInteger SEQ = new AtomicInteger();

  private static String uniqueEmail() {
    return "email-user-" + SEQ.incrementAndGet() + "-" + System.nanoTime() + "@test.kz";
  }

  private static RegisterRequest emailRegister(String email) {
    RegisterRequest req = new RegisterRequest();
    req.setEmail(email);
    req.setPassword("Test1234");
    req.setDisplayName("Email User");
    req.setTermsAccepted(true);
    return req;
  }

  @Test
  void registerByEmail_persistsVerifiedUserWithNoProfilePhoneInTestMode() {
    String email = uniqueEmail();

    AuthResponse response = authService.register(emailRegister(email), MailLocale.RU, null);

    assertNotNull(response.getAccessToken(), "test mode auto-verifies email and issues a session");
    User saved = userRepository.findByEmail(email).orElseThrow();
    assertEquals(email, saved.getEmail());
    assertNull(saved.getPhone(), "registration must not attach a profile phone");
    assertEquals(Boolean.TRUE, saved.getEmailVerified());
    assertNotNull(saved.getPublicId());
    assertNotNull(saved.getSlug());
  }

  @Test
  void fullEmailFlow_registerThenLogin_usesPublicIdJwtSubject() {
    String email = uniqueEmail();
    AuthResponse registered = authService.register(emailRegister(email), MailLocale.RU, null);
    assertNotNull(registered.getAccessToken());

    LoginRequest login = new LoginRequest();
    login.setEmail(email.toUpperCase());
    login.setPassword("Test1234");

    AuthResponse loggedIn = authService.login(login);
    assertNotNull(loggedIn.getAccessToken());
    assertNotNull(loggedIn.getRefreshToken());

    User user = userRepository.findByEmail(email).orElseThrow();
    assertEquals(user.getPublicId(), jwtUtil.extractUsername(loggedIn.getAccessToken()));
  }

  @Test
  void registerByEmail_duplicateEmailRejected() {
    String email = uniqueEmail();
    authService.register(emailRegister(email), MailLocale.RU, null);

    assertThrows(
        UserAlreadyExistsException.class,
        () -> authService.register(emailRegister(email.toUpperCase()), MailLocale.RU, null));
  }
}
