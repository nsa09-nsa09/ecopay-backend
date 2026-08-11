package kz.hrms.splitupauth.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import kz.hrms.splitupauth.payment.gateway.MockPaymentGateway;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
@RequiredArgsConstructor
public class ProductionStartupGuard implements ApplicationRunner {

  private final Environment environment;
  private final CorsProperties corsProperties;
  private final FreedomPayProperties freedomPayProperties;

  @Override
  public void run(ApplicationArguments args) {
    List<String> violations = new ArrayList<>();

    validateProfiles(violations);
    reject(
        MockPaymentGateway.PROVIDER_NAME.equalsIgnoreCase(prop("ecopay.payments.provider")),
        violations,
        "payment provider is mock");
    reject(
        !FreedomPayGateway.PROVIDER_NAME.equalsIgnoreCase(prop("ecopay.payments.provider")),
        violations,
        "payment provider is not production FreedomPay");
    reject(!isFreedomPayLiveMode(), violations, "FreedomPay test mode is enabled");
    reject(
        isSandboxOrTestHost(hostOf(freedomPayProperties.getBaseUrl())),
        violations,
        "FreedomPay base URL points to sandbox/test host");
    rejectBlank("ecopay.payments.freedompay.merchant-id", violations);
    rejectBlank("ecopay.payments.freedompay.secret-key", violations);
    rejectBlank("ecopay.payments.freedompay.payout-secret-key", violations);
    reject(!isStrongBase64Secret(prop("jwt.secret")), violations, "JWT secret is missing or weak");
    reject(
        !isStrongBase64Secret(prop("app.security.field-encryption-key")),
        violations,
        "field encryption key is missing or weak");
    reject(!isHttpsPublicUrl(prop("app.base-url")), violations, "backend public URL is not HTTPS");
    reject(
        !isHttpsPublicUrl(prop("app.frontend-url")),
        violations,
        "frontend public URL is not HTTPS");
    validateFreedomPayUrls(violations);
    validateCors(violations);
    validateSms(violations);
    reject(
        !"true".equalsIgnoreCase(prop("app.auth.refresh-cookie-secure")),
        violations,
        "refresh cookie secure flag is disabled");
    reject(
        !"false".equalsIgnoreCase(prop("springdoc.api-docs.enabled"))
            || !"false".equalsIgnoreCase(prop("springdoc.swagger-ui.enabled")),
        violations,
        "Swagger/OpenAPI is enabled");
    reject(
        "true".equalsIgnoreCase(prop("spring.jpa.show-sql")),
        violations,
        "SQL statement logging is enabled");
    reject(
        "false".equalsIgnoreCase(prop("spring.flyway.validate-on-migrate")),
        violations,
        "Flyway validation is disabled");
    reject(
        !prop("spring.flyway.ignore-migration-patterns").isBlank(),
        violations,
        "Flyway missing migration validation is weakened");
    reject(
        !List.of("framework", "native").contains(prop("server.forward-headers-strategy")),
        violations,
        "forwarded headers strategy is not configured for reverse proxy use");

    if (!violations.isEmpty()) {
      throw new IllegalStateException(
          "Refusing to start production profile due to unsafe configuration: "
              + String.join("; ", violations));
    }
  }

  private void validateProfiles(List<String> violations) {
    List<String> activeProfiles = List.of(environment.getActiveProfiles());
    reject(!activeProfiles.contains("prod"), violations, "prod profile is not active");
    reject(activeProfiles.contains("dev"), violations, "dev profile is active together with prod");
    reject(
        activeProfiles.contains("test"), violations, "test profile is active together with prod");
  }

  private void validateFreedomPayUrls(List<String> violations) {
    rejectUnsafeCallback(freedomPayProperties.getResultUrl(), violations, "FreedomPay result URL");
    rejectUnsafeCallback(
        freedomPayProperties.getPayoutResultUrl(), violations, "FreedomPay payout result URL");
    rejectUnsafeCallback(
        freedomPayProperties.getSuccessUrl(), violations, "FreedomPay success URL");
    rejectUnsafeCallback(
        freedomPayProperties.getFailureUrl(), violations, "FreedomPay failure URL");
  }

  private void validateCors(List<String> violations) {
    if (corsProperties.getAllowedOrigins().isEmpty()) {
      violations.add("CORS allowlist is empty");
      return;
    }
    for (String origin : corsProperties.getAllowedOrigins()) {
      String normalized = origin == null ? "" : origin.trim().toLowerCase(Locale.ROOT);
      reject(normalized.equals("*"), violations, "CORS allowlist contains wildcard");
      reject(
          normalized.startsWith("http://"), violations, "CORS allowlist contains non-HTTPS origin");
      reject(
          isPrivateOrLocalHost(hostOf(normalized)),
          violations,
          "CORS allowlist contains local/private origin");
    }
  }

  private void validateSms(List<String> violations) {
    String provider = prop("ecopay.sms.provider").trim().toLowerCase(Locale.ROOT);
    reject(provider.isBlank(), violations, "SMS provider is missing");
    reject(
        provider.equals("logging") || provider.equals("mock"),
        violations,
        "SMS provider is not real");
    reject(!provider.equals("mobizon"), violations, "SMS provider is not supported for production");
    rejectBlank("ecopay.sms.mobizon.base-url", violations);
    reject(
        !isHttpsPublicUrl(prop("ecopay.sms.mobizon.base-url")),
        violations,
        "Mobizon base URL is not HTTPS");
    rejectBlank("ecopay.sms.mobizon.api-key", violations);
    rejectBlank("ecopay.sms.mobizon.from", violations);
    reject(!prop("app.phone.dev-bypass-code").isBlank(), violations, "dev phone bypass is enabled");
  }

  private void rejectUnsafeCallback(String url, List<String> violations, String label) {
    reject(!isHttpsPublicUrl(url), violations, label + " is not HTTPS public URL");
    reject(isPrivateOrLocalHost(hostOf(url)), violations, label + " contains local/private host");
  }

  private boolean isFreedomPayLiveMode() {
    String testMode = freedomPayProperties.getTestMode();
    return testMode != null
        && (testMode.equals("0")
            || testMode.equalsIgnoreCase("false")
            || testMode.equalsIgnoreCase("off"));
  }

  private void rejectBlank(String propertyName, List<String> violations) {
    reject(prop(propertyName).isBlank(), violations, propertyName + " is missing");
  }

  private boolean isStrongBase64Secret(String value) {
    if (isBlankOrExample(value)) {
      return false;
    }
    try {
      return Base64.getDecoder().decode(value.trim()).length >= 32;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static boolean isBlankOrExample(String value) {
    if (value == null || value.isBlank()) {
      return true;
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    return normalized.contains("example")
        || normalized.contains("default")
        || normalized.contains("change-me")
        || normalized.contains("changeme")
        || normalized.equals("secret");
  }

  private static boolean isHttpsPublicUrl(String value) {
    try {
      URI uri = URI.create(value == null ? "" : value.trim());
      return "https".equalsIgnoreCase(uri.getScheme())
          && uri.getHost() != null
          && !isPrivateOrLocalHost(uri.getHost());
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static String hostOf(String value) {
    try {
      return URI.create(value == null ? "" : value.trim()).getHost();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static boolean isPrivateOrLocalHost(String host) {
    if (host == null || host.isBlank()) {
      return true;
    }
    String lower = host.toLowerCase(Locale.ROOT);
    if (lower.equals("localhost")
        || lower.equals("0.0.0.0")
        || lower.equals("127.0.0.1")
        || lower.equals("::1")
        || lower.endsWith(".localhost")
        || lower.endsWith(".local")) {
      return true;
    }
    if (lower.startsWith("10.") || lower.startsWith("192.168.")) {
      return true;
    }
    if (lower.startsWith("172.")) {
      String[] parts = lower.split("\\.");
      if (parts.length > 1) {
        try {
          int second = Integer.parseInt(parts[1]);
          return second >= 16 && second <= 31;
        } catch (NumberFormatException ignored) {
          return false;
        }
      }
    }
    return false;
  }

  private static boolean isSandboxOrTestHost(String host) {
    if (host == null || host.isBlank()) {
      return true;
    }
    String lower = host.toLowerCase(Locale.ROOT);
    return lower.contains("test") || lower.contains("sandbox") || lower.contains("stage");
  }

  private String prop(String name) {
    return environment.getProperty(name, "");
  }

  private static void reject(boolean condition, List<String> violations, String message) {
    if (condition) {
      violations.add(message);
    }
  }
}
