package kz.hrms.splitupauth.service;

import java.time.LocalDateTime;
import kz.hrms.splitupauth.entity.LoginAttempt;
import kz.hrms.splitupauth.exception.TooManyLoginAttemptsException;
import kz.hrms.splitupauth.repository.LoginAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RateLimitService {

  private final LoginAttemptRepository loginAttemptRepository;

  @Value("${app.rate-limit.login.attempts}")
  private Integer maxAttempts;

  @Value("${app.rate-limit.login.duration-minutes}")
  private Integer durationMinutes;

  /**
   * Per-source-IP failed-attempt ceiling, across all emails. Deliberately higher than the per-email
   * {@code maxAttempts} so a shared NAT/office IP with a few fat-fingering users isn't locked out,
   * while a credential-stuffing run that cycles through many accounts from one host is still caught.
   */
  @Value("${app.rate-limit.login.ip-attempts:20}")
  private Integer maxIpAttempts;

  /** Legacy entry point (per-email only). */
  @Transactional
  public void checkLoginAttempts(String email) {
    checkLoginAttempts(email, null);
  }

  /**
   * Enforces both the per-email bucket and, when an IP is known, the per-IP bucket. Both counts are
   * index-backed COUNT queries (no row materialization). Throws the same exception/message for
   * either, so the caller can't tell which bucket tripped.
   */
  @Transactional
  public void checkLoginAttempts(String email, String ip) {
    LocalDateTime thresholdTime = LocalDateTime.now().minusMinutes(durationMinutes);

    long failedByEmail =
        loginAttemptRepository.countByEmailAndSuccessfulFalseAndAttemptTimeAfter(
            email, thresholdTime);
    if (failedByEmail >= maxAttempts) {
      throw new TooManyLoginAttemptsException(
          "Too many failed login attempts. Please try again later.");
    }

    if (ip != null && !ip.isBlank()) {
      long failedByIp =
          loginAttemptRepository.countByIpAndSuccessfulFalseAndAttemptTimeAfter(ip, thresholdTime);
      if (failedByIp >= maxIpAttempts) {
        throw new TooManyLoginAttemptsException(
            "Too many failed login attempts. Please try again later.");
      }
    }
  }

  /** Legacy entry point (no IP recorded). */
  @Transactional
  public void recordLoginAttempt(String email, boolean successful) {
    recordLoginAttempt(email, successful, null);
  }

  @Transactional
  public void recordLoginAttempt(String email, boolean successful, String ip) {
    LoginAttempt attempt =
        LoginAttempt.builder().email(email).successful(successful).ip(ip).build();
    loginAttemptRepository.save(attempt);
  }

  @Transactional
  public void cleanupOldAttempts() {
    LocalDateTime thresholdTime = LocalDateTime.now().minusDays(1);
    loginAttemptRepository.deleteByAttemptTimeBefore(thresholdTime);
  }
}
