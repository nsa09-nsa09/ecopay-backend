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
        () -> new ProductionStartupGuard(validEnvironment(), corsProperties, liveFreedomPay()).run(null));
  }

  @Test
  void localhostCallbackFailsProductionStartup() {
    FreedomPayProperties freedomPay = liveFreedomPay();
    freedomPay.setResultUrl("http://localhost:8080/api/v1/webhooks/freedompay/result");

    assertThrows(
        IllegalStateException.class,
        () -> new ProductionStartupGuard(validEnvironment(), validCors(), freedomPay).run(null));
  }

  private ProductionStartupGuard guard(MockEnvironment environment) {
    return new ProductionStartupGuard(environment, validCors(), liveFreedomPay());
  }

  private static MockEnvironment validEnvironment() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");
    environment
        .withProperty("ecopay.payments.provider", "freedompay")
        .withProperty("jwt.secret", strongSecret())
        .withProperty("app.security.field-encryption-key", strongSecret())
        .withProperty("app.base-url", "https://api.ecopay.kz")
        .withProperty("app.frontend-url", "https://app.ecopay.kz")
        .withProperty("app.auth.refresh-cookie-secure", "true")
        .withProperty("springdoc.api-docs.enabled", "false")
        .withProperty("springdoc.swagger-ui.enabled", "false")
        .withProperty("spring.jpa.show-sql", "false")
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
