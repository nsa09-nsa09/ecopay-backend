package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.entity.LoginAttempt;
import kz.hrms.splitupauth.exception.TooManyLoginAttemptsException;
import kz.hrms.splitupauth.repository.LoginAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final LoginAttemptRepository loginAttemptRepository;

    @Value("${app.rate-limit.login.attempts}")
    private Integer maxAttempts;

    @Value("${app.rate-limit.login.duration-minutes}")
    private Integer durationMinutes;

    @Transactional
    public void checkLoginAttempts(String email) {
        LocalDateTime thresholdTime = LocalDateTime.now().minusMinutes(durationMinutes);
        long failedAttempts = loginAttemptRepository.countFailedSince(email, thresholdTime);

        if (failedAttempts >= maxAttempts) {
            throw new TooManyLoginAttemptsException(
                    "Too many failed login attempts. Please try again later."
            );
        }
    }

    @Transactional
    public void recordLoginAttempt(String email, boolean successful) {
        LoginAttempt attempt = LoginAttempt.builder()
                .email(email)
                .successful(successful)
                .build();
        loginAttemptRepository.save(attempt);
    }

    @Transactional
    public void cleanupOldAttempts() {
        LocalDateTime thresholdTime = LocalDateTime.now().minusDays(1);
        loginAttemptRepository.deleteOlderThan(thresholdTime);
    }
}
