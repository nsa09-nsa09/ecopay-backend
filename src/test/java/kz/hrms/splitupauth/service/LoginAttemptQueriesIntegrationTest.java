package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.exception.TooManyLoginAttemptsException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Lockout counting and cleanup now run as single SQL statements; behaviour must be unchanged. */
class LoginAttemptQueriesIntegrationTest extends AbstractIntegrationTest {

    @Autowired RateLimitService rateLimitService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void lockout_countsOnlyRecentFailures() {
        String email = "lock_" + UUID.randomUUID() + "@test.kz";
        for (int i = 0; i < 4; i++) rateLimitService.recordLoginAttempt(email, false);
        rateLimitService.recordLoginAttempt(email, true);
        assertDoesNotThrow(() -> rateLimitService.checkLoginAttempts(email), "4 failures < limit of 5");

        rateLimitService.recordLoginAttempt(email, false);
        assertThrows(TooManyLoginAttemptsException.class, () -> rateLimitService.checkLoginAttempts(email));

        jdbc.update("update login_attempts set attempt_time = now() - interval '1 hour' where email = ?", email);
        assertDoesNotThrow(() -> rateLimitService.checkLoginAttempts(email), "old failures fall out of the window");
    }

    @Test
    void cleanup_deletesOnlyAttemptsOlderThanADay() {
        String email = "clean_" + UUID.randomUUID() + "@test.kz";
        rateLimitService.recordLoginAttempt(email, false);
        rateLimitService.recordLoginAttempt(email, false);
        jdbc.update("update login_attempts set attempt_time = now() - interval '2 days' "
                + "where id = (select min(id) from login_attempts where email = ?)", email);

        rateLimitService.cleanupOldAttempts();

        assertEquals(1, jdbc.queryForObject("select count(*) from login_attempts where email = ?", Long.class, email));
    }
}
