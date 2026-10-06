package kz.hrms.splitupauth.service;

import java.time.LocalDateTime;
import kz.hrms.splitupauth.entity.LoginAttempt;
import kz.hrms.splitupauth.exception.TooManyLoginAttemptsException;
import kz.hrms.splitupauth.repository.LoginAttemptRepository;
import kz.hrms.splitupauth.util.ClientIp;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login throttling with two independent buckets: failures per account identifier (stops guessing
 * one password) and failures per source address (stops one host spraying many accounts). Both are
 * indexed COUNT queries on {@code login_attempts}, so the check is O(index range) regardless of how
 * many attempts a hostile client makes. The same generic exception is thrown for either bucket and
 * for unknown accounts, so the response never reveals whether an account exists.
 */
@Service
@RequiredArgsConstructor
public class RateLimitService {

  private final LoginAttemptRepository loginAttemptRepository;

  @Value("${app.rate-limit.login.attempts}")
  private Integer maxAttempts;

  @Value("${app.rate-limit.login.duration-minutes}")
  private Integer durationMinutes;

  /** Failed logins tolerated from one source address per window (0 disables the IP bucket). */
  @Value("${app.rate-limit.login.ip-attempts:30}")
  private Integer maxIpAttempts = 30;

  @Transactional(readOnly = true)
  public void checkLoginAttempts(String email) {
    LocalDateTime thresholdTime = LocalDateTime.now().minusMinutes(durationMinutes);
    long failedForAccount =
        loginAttemptRepository.countByEmailAndSuccessfulFalseAndAttemptTimeAfter(
            email, thresholdTime);
    boolean ipBlocked = false;
    String ip = ClientIp.current();
    if (maxIpAttempts != null && maxIpAttempts > 0 && !"unknown".equals(ip)) {
      ipBlocked =
          loginAttemptRepository.countByIpAddressAndSuccessfulFalseAndAttemptTimeAfter(
                  ip, thresholdTime)
              >= maxIpAttempts;
    }

    if (failedForAccount >= maxAttempts || ipBlocked) {
      throw new TooManyLoginAttemptsException(
          "Too many failed login attempts. Please try again later.");
    }
  }

  @Transactional
  public void recordLoginAttempt(String email, boolean successful) {
    String ip = ClientIp.current();
    LoginAttempt attempt =
        LoginAttempt.builder()
            .email(email)
            .successful(successful)
            .ipAddress("unknown".equals(ip) ? null : ip)
            .build();
    loginAttemptRepository.save(attempt);
  }

  @Transactional
  public void cleanupOldAttempts() {
    LocalDateTime thresholdTime = LocalDateTime.now().minusDays(1);
    loginAttemptRepository.deleteByAttemptTimeBefore(thresholdTime);
  }
}
