package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.dto.PasswordResetRequest;
import kz.hrms.splitupauth.dto.RegisterRequest;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.TooManyLoginAttemptsException;
import kz.hrms.splitupauth.exception.TooManyRequestsException;
import kz.hrms.splitupauth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

/** Block 4 anti-abuse behaviour against a real Postgres + the real wired limiter/config. */
class AuthAntiAbuseIntegrationTest extends AbstractIntegrationTest {

  @Autowired AuthService authService;
  @Autowired RateLimitService rateLimitService;
  @Autowired UserRepository userRepository;
  @Autowired JdbcTemplate jdbcTemplate;

  @Value("${app.rate-limit.register.ip-max}")
  int registerIpMax;

  @Value("${app.rate-limit.password-reset.ip-max}")
  int passwordResetIpMax;

  @Value("${app.rate-limit.password-reset.account-max}")
  int passwordResetAccountMax;

  @Value("${app.rate-limit.login.ip-attempts}")
  int loginIpAttempts;

  private static final AtomicInteger SEQ = new AtomicInteger();

  private MockHttpServletRequest requestFrom(String ip) {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setRemoteAddr(ip);
    return req;
  }

  private RegisterRequest registerReq(String email) {
    RegisterRequest req = new RegisterRequest();
    req.setEmail(email);
    req.setPassword("Test1234");
    req.setDisplayName("AB Tester");
    return req;
  }

  private String freshEmail() {
    return "ab_" + SEQ.incrementAndGet() + "_" + System.nanoTime() + "@test.kz";
  }

  @Test
  void registrationStormFromOneIp_isBlocked() {
    MockHttpServletRequest http = requestFrom("203.0.113.21");
    // Distinct emails so neither existsByEmail nor the per-email bucket interferes — this isolates
    // the per-IP cap, which is the "unverified-account creation storm" defence.
    for (int i = 0; i < registerIpMax; i++) {
      authService.register(registerReq(freshEmail()), MailLocale.RU, http);
    }
    assertThrows(
        TooManyRequestsException.class,
        () -> authService.register(registerReq(freshEmail()), MailLocale.RU, http));

    // A different source IP is unaffected.
    assertDoesNotThrow(
        () ->
            authService.register(
                registerReq(freshEmail()), MailLocale.RU, requestFrom("198.51.100.21")));
  }

  @Test
  void passwordResetStormFromOneIp_isBlocked_evenForUnknownAddresses() {
    MockHttpServletRequest http = requestFrom("203.0.113.22");
    PasswordResetRequest unknown = new PasswordResetRequest();
    unknown.setEmail("nobody_" + System.nanoTime() + "@test.kz");

    // The unknown address never throws on its own (no enumeration oracle); the per-IP cap is what
    // eventually trips, and it does so regardless of whether the address exists.
    for (int i = 0; i < passwordResetIpMax; i++) {
      assertDoesNotThrow(() -> authService.requestPasswordReset(unknown, http));
    }
    assertThrows(
        TooManyRequestsException.class, () -> authService.requestPasswordReset(unknown, http));
  }

  @Test
  void passwordResetPerAccountCooldown_isSilent_andStopsMintingTokens() {
    // A verified account (devAutoVerifyEmail=true in the test profile verifies the email on sign-up).
    String email = freshEmail();
    authService.register(registerReq(email), MailLocale.RU, null);
    User user = userRepository.findByEmail(email).orElseThrow();

    PasswordResetRequest req = new PasswordResetRequest();
    req.setEmail(email);

    // No HttpServletRequest → no per-IP throttle; this isolates the per-ACCOUNT cooldown. It must
    // NEVER throw (silent), or the response would distinguish a known verified account.
    for (int i = 0; i < passwordResetAccountMax; i++) {
      assertDoesNotThrow(() -> authService.requestPasswordReset(req, null));
    }
    String tokenAfterCap = currentResetToken(user.getId());
    assertNotNull(tokenAfterCap, "a reset token should have been minted within the cooldown budget");

    // Further calls stay silent AND stop minting new tokens (the cooldown is doing real work).
    for (int i = 0; i < 3; i++) {
      assertDoesNotThrow(() -> authService.requestPasswordReset(req, null));
    }
    assertEquals(
        tokenAfterCap,
        currentResetToken(user.getId()),
        "cooldown must silently skip minting a new token once the per-account cap is hit");
  }

  @Test
  void loginStuffingFromOneIpAcrossManyEmails_isBlocked() {
    String ip = "203.0.113.23";
    // Many DISTINCT emails, all failing from the same IP — the per-email bucket never sees enough
    // for any single address, but the per-IP bucket accumulates.
    for (int i = 0; i < loginIpAttempts; i++) {
      rateLimitService.recordLoginAttempt(freshEmail(), false, ip);
    }

    // A brand-new email with zero failures of its own is still blocked from this IP.
    assertThrows(
        TooManyLoginAttemptsException.class,
        () -> rateLimitService.checkLoginAttempts(freshEmail(), ip));

    // The same fresh email from a clean IP is fine.
    assertDoesNotThrow(() -> rateLimitService.checkLoginAttempts(freshEmail(), "198.51.100.23"));
  }

  private String currentResetToken(Long userId) {
    return jdbcTemplate.query(
        "SELECT token FROM password_reset_tokens WHERE user_id = ?",
        rs -> rs.next() ? rs.getString(1) : null,
        userId);
  }
}
