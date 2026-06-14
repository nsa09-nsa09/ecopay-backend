package kz.hrms.splitupauth;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for integration tests: boots the full Spring context against a real
 * PostgreSQL (Testcontainers), so Flyway migrations (incl. V7 seed + V8
 * append-only triggers), real transactions and SQL constraints are exercised —
 * unlike the Mockito unit tests. The dev mock payment gateway and the phone
 * dev-bypass code are enabled so the chain can run without external services.
 *
 * <p>The PostgreSQL container uses the "singleton container" pattern (started
 * once in a static initializer, never stopped) so several @SpringBootTest
 * classes can share the same DB without the JVM tearing it down between them.
 * Spring caches contexts across classes — if the container were stopped via
 * JUnit lifecycle, a reused context would point at a dead connection pool.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
        POSTGRES.start();
        // No corresponding stop(): Ryuk reaps the container at JVM exit, and
        // killing it between classes would invalidate any cached Spring DataSource.
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // jwt.secret is Base64-decoded into the HMAC key (needs >= 32 bytes). This is
        // Base64 of a 48-char string.
        registry.add("jwt.secret",
                () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVm");
        // AesFieldEncryptionService requires a Base64 key decoding to 32 bytes.
        // (Base64 of the 32-char string "0123456789abcdef0123456789abcdef".)
        registry.add("app.security.field-encryption-key",
                () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("ecopay.payments.provider", () -> "mock");
        registry.add("ecopay.sms.provider", () -> "logging");
        registry.add("app.phone.dev-bypass-code", () -> "000000");
        // Dummy mail config so MailSenderAutoConfiguration can resolve (mail is never sent in tests).
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", () -> "1025");
        registry.add("spring.mail.username", () -> "test@test.kz");
        registry.add("spring.mail.password", () -> "test");
    }
}
