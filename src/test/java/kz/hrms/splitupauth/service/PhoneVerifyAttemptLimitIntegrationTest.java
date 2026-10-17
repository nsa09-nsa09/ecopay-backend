package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.dto.RegisterRequest;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.InvalidVerificationCodeException;
import kz.hrms.splitupauth.exception.TooManySmsAttemptsException;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.sms.SmsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The wrong-code attempt counter, against a real transaction manager.
 *
 * <p>This needs the real proxy: the counter is incremented and then the method throws, so with a
 * plain {@code @Transactional} the increment is rolled back with the exception and the cap silently
 * never fires — a six-digit code becomes guessable for its whole TTL. A Mockito unit test cannot
 * see that, because nothing there rolls anything back.
 */
class PhoneVerifyAttemptLimitIntegrationTest extends AbstractIntegrationTest {

  @Autowired AuthService authService;
  @Autowired PhoneVerificationService phoneVerificationService;
  @Autowired UserRepository userRepository;
  @Autowired SmsProperties smsProperties;
  @Autowired JdbcTemplate jdbcTemplate;

  private static final AtomicInteger SEQ = new AtomicInteger();

  private User register() {
    int n = SEQ.incrementAndGet();
    RegisterRequest req = new RegisterRequest();
    req.setEmail("attempts_" + n + "_" + System.nanoTime() + "@test.kz");
    req.setPassword("Test1234");
    req.setDisplayName("Attempt Limit");
    authService.register(req, MailLocale.RU, null);
    return userRepository.findByEmail(req.getEmail()).orElseThrow();
  }

  private String uniquePhone() {
    return "+77" + String.format("%09d", System.nanoTime() % 1_000_000_000L);
  }

  private int storedAttempts(String phone) {
    Integer attempts =
        jdbcTemplate.queryForObject(
            "SELECT attempts FROM phone_verifications WHERE phone = ?", Integer.class, phone);
    return attempts == null ? 0 : attempts;
  }

  @Test
  void aWrongCodeIsCounted_andTheCapEventuallyBlocks() {
    User user = register();
    String phone = uniquePhone();
    phoneVerificationService.requestCode(user, phone, null);

    int cap = smsProperties.getMaxVerifyAttempts();

    for (int i = 1; i <= cap; i++) {
      assertThrows(
          InvalidVerificationCodeException.class,
          () -> phoneVerificationService.verifyCode(user, phone, "111111"),
          "guess should be rejected as invalid, not as blocked, while under the cap");
      assertEquals(i, storedAttempts(phone), "attempt " + i + " must survive the rollback");
    }

    // Cap reached: further guesses are refused outright, so the remaining code
    // space cannot be walked.
    assertThrows(
        TooManySmsAttemptsException.class,
        () -> phoneVerificationService.verifyCode(user, phone, "111111"));
    assertEquals(cap, storedAttempts(phone), "a blocked guess must not inflate the counter");
  }

  @Test
  void aBlockedCodeStaysBlockedEvenIfTheGuessIsRight() {
    User user = register();
    String phone = uniquePhone();
    phoneVerificationService.requestCode(user, phone, null);

    for (int i = 0; i < smsProperties.getMaxVerifyAttempts(); i++) {
      assertThrows(
          InvalidVerificationCodeException.class,
          () -> phoneVerificationService.verifyCode(user, phone, "111111"));
    }

    // The real code is unknown to the test, but the cap is checked before the
    // comparison — so even a correct guess has to be turned away here.
    assertThrows(
        TooManySmsAttemptsException.class,
        () -> phoneVerificationService.verifyCode(user, phone, "000001"));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM phone_verifications WHERE phone = ? AND verified_at IS NOT NULL",
            Integer.class,
            phone),
        "a blocked code must not end up marked verified");
  }
}
