package kz.hrms.splitupauth.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Base64;
import java.util.List;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionStartupGuardTest {

  @Test
  void validProductionSettingsPass() {
    assertDoesNotThrow(() -> guard(validEnvironment()).run(null));
  }

  @Test
  void mockPaymentProviderFailsProductionStartup() {
    MockEnvironment environment = validEnvironment();
    environment.setProperty("ecopay.payments.provider", "mock");

    assertThrows(IllegalStateException.class, () -> guard(environment).run(null));
  }

  @Test
  void wildcardCorsFailsProductionStartup() {
    CorsProperties corsProperties = new CorsProperties();
    corsProperties.setAllowedOrigins(List.of("*"));

    assertThrows(
        IllegalStateException.class,
        () ->
            new ProductionStartupGuard(validEnvironment(), corsProperties, liveFreedomPay())
                .run(null));
  }

  @Test
  void localhostCallbackFailsProductionStartup() {
    FreedomPayProperties freedomPay = liveFreedomPay();
    freedomPay.setResultUrl("http://localhost:8080/api/v1/webhooks/freedompay/result");

    assertThrows(
        IllegalStateException.class,
        () -> new ProductionStartupGuard(validEnvironment(), validCors(), freedomPay).run(null));
  }

  @Test
  void devPhoneBypassFailsProductionStartup() {
    MockEnvironment environment = validEnvironment();
    environment.setProperty("app.phone.dev-bypass-code", "000000");

    assertThrows(IllegalStateException.class, () -> guard(environment).run(null));
  }

  @Test
  void loggingSmsFailsProductionStartup() {
    MockEnvironment environment = validEnvironment();
    environment.setProperty("ecopay.sms.provider", "logging");

    assertThrows(IllegalStateException.class, () -> guard(environment).run(null));
  }

  @Test
  void weakenedFlywayValidationFailsProductionStartup() {
    MockEnvironment environment = validEnvironment();
    environment.setProperty("spring.flyway.ignore-migration-patterns", "*:missing");

    assertThrows(IllegalStateException.class, () -> guard(environment).run(null));
  }

  @Test
  void recurringEnabledFailsProductionStartup() {
    MockEnvironment environment = validEnvironment();
    environment.setProperty("app.recurring.enabled", "true");

    assertThrows(IllegalStateException.class, () -> guard(environment).run(null));
  }

  @Test
  void missingObjectStorageFailsProductionStartup() {
    MockEnvironment environment = validEnvironment();
    environment.setProperty("app.s3.endpoint", "");

    assertThrows(IllegalStateException.class, () -> guard(environment).run(null));
  }

  @Test
  void unreviewedLegalDocumentsFailProductionStartup() {
    MockEnvironment environment = validEnvironment();
    environment.setProperty("app.production.legal-reviewed", "false");

    assertThrows(IllegalStateException.class, () -> guard(environment).run(null));
  }

  private ProductionStartupGuard guard(MockEnvironment environment) {
    return new ProductionStartupGuard(environment, validCors(), liveFreedomPay());
  }

  private static MockEnvironment validEnvironment() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");
    environment
        .withProperty("ecopay.payments.provider", "freedompay")
        .withProperty("ecopay.payments.freedompay.merchant-id", "live-merchant")
        .withProperty("ecopay.payments.freedompay.secret-key", "live-secret")
        .withProperty("ecopay.payments.freedompay.payout-secret-key", "live-payout-secret")
        .withProperty("jwt.secret", strongSecret())
        .withProperty("app.security.field-encryption-key", strongSecret())
        .withProperty("app.base-url", "https://api.ecopay.kz")
        .withProperty("app.frontend-url", "https://app.ecopay.kz")
        .withProperty("ecopay.sms.provider", "mobizon")
        .withProperty("ecopay.sms.mobizon.base-url", "https://api.mobizon.kz")
        .withProperty("ecopay.sms.mobizon.api-key", "live-mobizon-key")
        .withProperty("ecopay.sms.mobizon.from", "EcoPay")
        .withProperty("app.phone.dev-bypass-code", "")
        .withProperty("spring.mail.host", "smtp.ecopay.kz")
        .withProperty("spring.mail.username", "mailer@ecopay.kz")
        .withProperty("spring.mail.password", "live-mail-password")
        .withProperty("spring.mail.properties.mail.smtp.from", "mailer@ecopay.kz")
        .withProperty("app.s3.region", "auto")
        .withProperty("app.s3.bucket", "ecopay-prod")
        .withProperty("app.s3.endpoint", "https://example.r2.cloudflarestorage.com")
        .withProperty("app.s3.access-key", "live-s3-access")
        .withProperty("app.s3.secret-key", "live-s3-secret")
        .withProperty("app.brand.support-email", "support@ecopay.kz")
        .withProperty("app.production.legal-entity-name", "EcoPay LLP")
        .withProperty("app.production.legal-bin", "123456789012")
        .withProperty("app.production.legal-address", "Astana, Kazakhstan")
        .withProperty("app.production.legal-reviewed", "true")
        .withProperty("app.recurring.enabled", "false")
        .withProperty("app.auth.refresh-cookie-secure", "true")
        .withProperty("springdoc.api-docs.enabled", "false")
        .withProperty("springdoc.swagger-ui.enabled", "false")
        .withProperty("spring.jpa.show-sql", "false")
        .withProperty("spring.flyway.validate-on-migrate", "true")
        .withProperty("spring.flyway.ignore-migration-patterns", "")
        .withProperty("server.forward-headers-strategy", "framework");
    return environment;
  }

  private static CorsProperties validCors() {
    CorsProperties corsProperties = new CorsProperties();
    corsProperties.setAllowedOrigins(List.of("https://app.ecopay.kz"));
    return corsProperties;
  }

  private static FreedomPayProperties liveFreedomPay() {
    FreedomPayProperties properties = new FreedomPayProperties();
    properties.setBaseUrl("https://api.freedompay.kz");
    properties.setMerchantId("live-merchant");
    properties.setSecretKey("live-secret");
    properties.setPayoutSecretKey("live-payout-secret");
    properties.setTestMode("0");
    properties.setResultUrl("https://api.ecopay.kz/api/v1/webhooks/freedompay/result");
    properties.setPayoutResultUrl("https://api.ecopay.kz/api/v1/webhooks/freedompay/payout-result");
    properties.setSuccessUrl("https://app.ecopay.kz/payment/confirmation");
    properties.setFailureUrl("https://app.ecopay.kz/payment/failure");
    return properties;
  }

  private static String strongSecret() {
    return Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
  }
}
