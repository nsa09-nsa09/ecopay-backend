package kz.hrms.splitupauth.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/**
 * Probes the SMTP server once at startup and logs the result.
 *
 * <p>Deliberately non-fatal: mail is not on the critical path for browsing the catalog or logging
 * in with an already-verified account, so a mail outage must not take the whole service down. The
 * point is that a misconfigured MAIL_HOST shows up as a loud WARN in the boot log instead of as a
 * user's failed registration hours later.
 *
 * <p>Runs on a daemon thread so a hanging SMTP host cannot stall application startup.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SmtpHealthCheck {

  private final JavaMailSenderImpl mailSender;

  @Value("${app.email.startup-check.enabled:true}")
  private boolean enabled;

  @PostConstruct
  public void verifyConnection() {
    if (!enabled) {
      log.info("SMTP startup check disabled");
      return;
    }

    Thread probe =
        new Thread(
            () -> {
              try {
                mailSender.testConnection();
                log.info(
                    "SMTP reachable at {}:{} — transactional email is available",
                    mailSender.getHost(),
                    mailSender.getPort());
              } catch (Exception e) {
                log.warn(
                    "SMTP check FAILED for {}:{} — {}. Verification and password-reset emails will"
                        + " not be delivered until this is fixed.",
                    mailSender.getHost(),
                    mailSender.getPort(),
                    e.getMessage());
              }
            },
            "smtp-startup-check");
    probe.setDaemon(true);
    probe.start();
  }
}
