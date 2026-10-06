package kz.hrms.splitupauth.sms;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Dev/test SMS sink: prints the code instead of sending it, so local flows work without a provider
 * contract. It is the fallback when {@code ecopay.sms.provider} is unset, which is convenient in
 * development and dangerous anywhere else — hence the startup guard below.
 */
@Slf4j
@Component
@ConditionalOnProperty(
    prefix = "ecopay.sms",
    name = "provider",
    havingValue = "logging",
    matchIfMissing = true)
public class LoggingSmsProvider implements SmsService {

  private final Environment environment;

  @Value("${ecopay.sms.provider:}")
  private String configuredProvider;

  public LoggingSmsProvider(Environment environment) {
    this.environment = environment;
  }

  /**
   * Refuses to boot a production profile on this provider. Without the check, forgetting {@code
   * ecopay.sms.provider} in prod silently yields a system that never sends an SMS and writes every
   * verification code to the application log — a failure that looks like "users report codes never
   * arrive" and quietly leaks codes to anyone with log access.
   */
  @PostConstruct
  void rejectSilentProductionUse() {
    boolean prod = environment.matchesProfiles("prod");
    if (!prod) {
      return;
    }
    throw new IllegalStateException(
        "SMS provider 'logging' writes verification codes to the log and sends nothing — "
            + "it must not run under the prod profile. Set ecopay.sms.provider to a real "
            + "provider (currently: '"
            + (configuredProvider == null || configuredProvider.isBlank()
                ? "<unset>"
                : configuredProvider)
            + "').");
  }

  @Override
  public void sendVerificationCode(String phone, String code) {
    log.warn("[SMS:LOGGING] Verification code for {} is {}", phone, code);
  }
}
