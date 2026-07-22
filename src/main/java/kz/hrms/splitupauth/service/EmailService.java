package kz.hrms.splitupauth.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import kz.hrms.splitupauth.exception.MailDeliveryException;
import kz.hrms.splitupauth.util.EmailNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * All outgoing transactional mail. Every message is rendered in the recipient's {@link MailLocale}
 * and sent through {@link #send}, which retries transient SMTP failures and converts a final
 * failure into {@link MailDeliveryException} (→ 503 "try again shortly") rather than letting a raw
 * SMTP error surface as a 500.
 *
 * <p><b>Logging rule:</b> we log that a message of some type was sent to a masked address, and
 * nothing else. Message bodies, verification codes and tokens must never reach the logs — logs are
 * shipped off-host and a leaked code is a full account takeover.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

  private final JavaMailSender mailSender;

  @Value("${spring.mail.username:}")
  private String fromEmail;

  @Value("${app.base-url:http://localhost:8080}")
  private String baseUrl;

  @Value("${app.frontend-url:http://localhost:5173}")
  private String frontendUrl;

  /** Total attempts per message, including the first. */
  @Value("${app.email.send.max-attempts:3}")
  private int maxAttempts;

  /** Delay before the 2nd attempt; doubles for each subsequent one. */
  @Value("${app.email.send.retry-backoff-ms:500}")
  private long retryBackoffMs;

  // ---------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------

  public void sendPasswordResetEmail(String to, String token, MailLocale locale) {
    String resetLink = sanitizeFrontendBase(frontendUrl) + "/reset-password/confirm?token=" + token;
    send(
        to,
        locale.pick("Сброс пароля", "Құпия сөзді қалпына келтіру", "Password reset"),
        buildPasswordResetEmail(resetLink, locale),
        "password-reset");
  }

  public void sendStaffTwoFactorCode(String to, String code, MailLocale locale) {
    send(
        to,
        locale.pick("Код входа в EcoPay", "EcoPay-ге кіру коды", "Your EcoPay sign-in code"),
        buildStaffTwoFactorEmail(code, locale),
        "staff-2fa");
  }

  /**
   * Confirmation email carrying the 6-digit code the user types on the registration screen. The
   * click-through link is kept as a fallback for users who prefer it.
   */
  public void sendVerificationEmail(String to, String token, String code, MailLocale locale) {
    String verifyLink = baseUrl + "/api/v1/auth/verify-email?token=" + token;
    send(
        to,
        locale.pick("Подтвердите ваш email", "Email-ді растаңыз", "Verify your email address"),
        buildVerificationEmail(verifyLink, code, locale),
        "email-verification");
  }

  /**
   * Confirmation email for adding or changing the account email from the profile. Sent to the NEW
   * address; the account keeps its old email (or none) until the code or link is confirmed.
   */
  public void sendEmailChangeConfirmation(String to, String token, String code, MailLocale locale) {
    String verifyLink = baseUrl + "/api/v1/auth/verify-email?token=" + token;
    send(
        to,
        locale.pick(
            "Подтвердите адрес электронной почты",
            "Электрондық пошта мекенжайын растаңыз",
            "Confirm your email address"),
        buildEmailChangeEmail(verifyLink, code, locale),
        "email-change");
  }

  /**
   * Generic transactional-notification email. Used by the notification system for email-eligible
   * events the user hasn't opted out of. {@code link} is an optional frontend-relative path (e.g.
   * {@code /rooms/member/42}) rendered as a "View details" button.
   *
   * <p>Subject and body arrive already composed (and already localized) from NotificationService.
   */
  public void sendNotificationEmail(
      String to, String subject, String body, String link, MailLocale locale) {
    send(to, subject, buildNotificationEmail(subject, body, link, locale), "notification");
  }

  // ---------------------------------------------------------------------
  // Delivery
  // ---------------------------------------------------------------------

  /**
   * Sends one HTML message, retrying transient failures with exponential backoff.
   *
   * @param kind short label for logs ("email-verification", …) — never the message content
   * @throws MailDeliveryException when every attempt fails
   */
  private void send(String to, String subject, String html, String kind) {
    String masked = EmailNormalizer.mask(to);
    MailException lastFailure = null;

    for (int attempt = 1; attempt <= Math.max(1, maxAttempts); attempt++) {
      try {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(fromEmail.trim());
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(html, true);

        mailSender.send(message);

        log.info("Sent '{}' email to {} (attempt {})", kind, masked, attempt);
        return;
      } catch (MessagingException e) {
        // Malformed message — building it again will fail identically, so do
        // not burn retries on it.
        log.error("Could not build '{}' email for {}: {}", kind, masked, e.getMessage());
        throw new MailDeliveryException("Unable to send email right now", e);
      } catch (MailException e) {
        lastFailure = e;
        log.warn(
            "Attempt {}/{} to send '{}' email to {} failed: {}",
            attempt,
            maxAttempts,
            kind,
            masked,
            e.getMessage());
        if (attempt < maxAttempts) {
          sleepBackoff(attempt);
        }
      }
    }

    log.error("Giving up on '{}' email to {} after {} attempts", kind, masked, maxAttempts);
    throw new MailDeliveryException("Unable to send email right now", lastFailure);
  }

  /** Exponential backoff: 500ms, 1s, 2s… Interruption aborts the retry loop promptly. */
  private void sleepBackoff(int attempt) {
    try {
      Thread.sleep(retryBackoffMs * (1L << (attempt - 1)));
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new MailDeliveryException("Email sending interrupted", ie);
    }
  }

  // ---------------------------------------------------------------------
  // Templates
  // ---------------------------------------------------------------------

  private String buildStaffTwoFactorEmail(String code, MailLocale locale) {
    return wrap(
        locale.pick("Код подтверждения входа", "Кіруді растау коды", "Sign-in verification code"),
        "<p>"
            + locale.pick(
                "Используйте код ниже, чтобы завершить вход в консоль персонала EcoPay:",
                "EcoPay қызметкерлер консоліне кіруді аяқтау үшін төмендегі кодты пайдаланыңыз:",
                "Use the code below to finish signing in to the EcoPay staff console:")
            + "</p>"
            + codeBlock(code)
            + "<p>"
            + locale.pick(
                "Код действует несколько минут. Если вы не пытались войти, немедленно смените"
                    + " пароль.",
                "Код бірнеше минут жарамды. Егер сіз кіруге әрекеттенбесеңіз, құпия сөзді дереу"
                    + " ауыстырыңыз.",
                "This code expires in a few minutes. If you did not try to sign in, please change"
                    + " your password immediately.")
            + "</p>");
  }

  private String buildVerificationEmail(String verifyLink, String code, MailLocale locale) {
    return wrap(
        locale.pick("Подтвердите ваш email", "Email-ді растаңыз", "Verify your email address"),
        "<p>"
            + locale.pick(
                "Спасибо за регистрацию! Введите код ниже на экране регистрации EcoPay, чтобы"
                    + " подтвердить почту и активировать аккаунт:",
                "Тіркелгеніңіз үшін рахмет! Поштаңызды растап, аккаунтты іске қосу үшін EcoPay"
                    + " тіркелу экранында төмендегі кодты енгізіңіз:",
                "Thanks for signing up! Enter the code below on the EcoPay registration screen to"
                    + " confirm your email and activate your account:")
            + "</p>"
            + codeBlock(code)
            + "<p>"
            + locale.pick(
                "Код действует 30 минут. Если он истёк, запросите новый на экране подтверждения.",
                "Код 30 минут жарамды. Мерзімі өтсе, растау экранында жаңасын сұратыңыз.",
                "This code expires in 30 minutes. If it has expired, request a new one on the"
                    + " confirmation screen.")
            + "</p>"
            + linkFallback(
                verifyLink,
                locale.pick("Подтвердить email", "Email-ді растау", "Verify email"),
                locale)
            + ignoreNotice(locale));
  }

  private String buildEmailChangeEmail(String verifyLink, String code, MailLocale locale) {
    return wrap(
        locale.pick(
            "Подтвердите адрес электронной почты",
            "Электрондық пошта мекенжайын растаңыз",
            "Confirm your email address"),
        "<p>"
            + locale.pick(
                "Введите код ниже в профиле EcoPay, чтобы привязать этот адрес к аккаунту:",
                "Осы мекенжайды аккаунтқа байлау үшін EcoPay профиліндегі төмендегі кодты"
                    + " енгізіңіз:",
                "Enter the code below in your EcoPay profile to attach this email address to your"
                    + " account:")
            + "</p>"
            + codeBlock(code)
            + "<p>"
            + locale.pick(
                "Код действует 30 минут.",
                "Код 30 минут жарамды.",
                "This code expires in 30 minutes.")
            + "</p>"
            + linkFallback(
                verifyLink,
                locale.pick("Подтвердить email", "Email-ді растау", "Confirm email"),
                locale)
            + ignoreNotice(locale));
  }

  private String buildPasswordResetEmail(String resetLink, MailLocale locale) {
    return wrap(
        locale.pick("Сброс пароля", "Құпия сөзді қалпына келтіру", "Password reset"),
        "<p>"
            + locale.pick(
                "Нажмите на ссылку ниже, чтобы задать новый пароль:",
                "Жаңа құпия сөз орнату үшін төмендегі сілтемені басыңыз:",
                "Click the link below to set a new password:")
            + "</p>"
            + "<p><a href=\""
            + resetLink
            + "\">"
            + locale.pick("Сбросить пароль", "Құпия сөзді қалпына келтіру", "Reset password")
            + "</a></p>"
            + "<p>"
            + locale.pick(
                "Ссылка действует 30 минут.",
                "Сілтеме 30 минут жарамды.",
                "This link expires in 30 minutes.")
            + "</p>"
            + ignoreNotice(locale));
  }

  private String buildNotificationEmail(String title, String body, String link, MailLocale locale) {
    StringBuilder html = new StringBuilder();
    html.append("<p>").append(escape(body)).append("</p>");
    if (link != null && !link.isBlank()) {
      String absolute = link.startsWith("http") ? link : sanitizeFrontendBase(frontendUrl) + link;
      html.append("<p><a href=\"")
          .append(absolute)
          .append("\">")
          .append(locale.pick("Подробнее", "Толығырақ", "View details"))
          .append("</a></p>");
    }
    html.append("<hr><p style=\"color:#888;font-size:12px;\">")
        .append(
            locale.pick(
                "Управлять письмами можно в настройках EcoPay.",
                "Хаттарды EcoPay параметрлерінде басқаруға болады.",
                "You can manage which emails you receive in EcoPay settings."))
        .append("</p>");
    return wrap(escape(title), html.toString());
  }

  /** Shared HTML skeleton so every message renders the same way. */
  private String wrap(String heading, String bodyHtml) {
    return "<html><body style=\"font-family:Arial,Helvetica,sans-serif;color:#111;\">"
        + "<h2>"
        + heading
        + "</h2>"
        + bodyHtml
        + "</body></html>";
  }

  private String codeBlock(String code) {
    return "<p style=\"font-size:24px;font-weight:bold;letter-spacing:6px;\">" + code + "</p>";
  }

  private String linkFallback(String href, String label, MailLocale locale) {
    return "<p>"
        + locale.pick(
            "Предпочитаете ссылку? Подтвердить можно здесь: ",
            "Сілтемені қалайсыз ба? Мұнда растауға болады: ",
            "Prefer a link? You can also confirm here: ")
        + "<a href=\""
        + href
        + "\">"
        + label
        + "</a></p>";
  }

  private String ignoreNotice(MailLocale locale) {
    return "<p>"
        + locale.pick(
            "Если вы этого не запрашивали, просто проигнорируйте это письмо.",
            "Егер сіз мұны сұрамаған болсаңыз, бұл хатты елемеңіз.",
            "If you didn't request this, please ignore this email.")
        + "</p>";
  }

  /**
   * Strips the Vite dev port (:5173) from the configured frontend base so it never leaks into any
   * user-facing link (password-reset, notification links, etc.). Also trims trailing slashes.
   */
  private static String sanitizeFrontendBase(String url) {
    if (url == null) return "";
    return url.replaceAll("/+$", "").replaceFirst(":5173(?=/|$)", "");
  }

  /** Minimal HTML-escaping so user-derived title/body can't inject markup. */
  private String escape(String s) {
    if (s == null) {
      return "";
    }
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
