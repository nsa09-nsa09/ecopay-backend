package kz.hrms.splitupauth.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailService {

  private final JavaMailSender mailSender;

  @Value("${spring.mail.username}")
  private String fromEmail;

  @Value("${app.base-url:http://localhost:8080}")
  private String baseUrl;

  @Value("${app.frontend-url:http://localhost:5173}")
  private String frontendUrl;

  public void sendPasswordResetEmail(String to, String token) {
    try {
      MimeMessage message = mailSender.createMimeMessage();
      MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

      helper.setFrom(fromEmail.trim());
      helper.setTo(to);
      helper.setSubject("Password Reset Request");

      String resetLink =
          sanitizeFrontendBase(frontendUrl) + "/reset-password/confirm?token=" + token;
      String htmlContent = buildPasswordResetEmail(resetLink);

      helper.setText(htmlContent, true);

      mailSender.send(message);
    } catch (MessagingException e) {
      throw new RuntimeException("Failed to send email", e);
    }
  }

  public void sendStaffTwoFactorCode(String to, String code) {
    try {
      MimeMessage message = mailSender.createMimeMessage();
      MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

      helper.setFrom(fromEmail.trim());
      helper.setTo(to);
      helper.setSubject("Your EcoPay sign-in code");

      helper.setText(buildStaffTwoFactorEmail(code), true);

      mailSender.send(message);
    } catch (MessagingException e) {
      throw new RuntimeException("Failed to send email", e);
    }
  }

  private String buildStaffTwoFactorEmail(String code) {
    return "<html>"
        + "<body>"
        + "<h2>Sign-in verification code</h2>"
        + "<p>Use the code below to finish signing in to the EcoPay staff console:</p>"
        + "<p style=\"font-size:24px;font-weight:bold;letter-spacing:6px;\">"
        + code
        + "</p>"
        + "<p>This code will expire in a few minutes. If you did not try to sign in, "
        + "please change your password immediately.</p>"
        + "</body>"
        + "</html>";
  }

  /**
   * Confirmation email carrying the 6-digit code the user types on the registration screen. The
   * click-through link is kept as a fallback for users who prefer it.
   */
  public void sendVerificationEmail(String to, String token, String code) {
    try {
      MimeMessage message = mailSender.createMimeMessage();
      MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

      helper.setFrom(fromEmail.trim());
      helper.setTo(to);
      helper.setSubject("Verify your email address");

      String verifyLink = baseUrl + "/api/v1/auth/verify-email?token=" + token;
      String htmlContent = buildVerificationEmail(verifyLink, code);

      helper.setText(htmlContent, true);

      mailSender.send(message);
    } catch (MessagingException e) {
      throw new RuntimeException("Failed to send email", e);
    }
  }

  /**
   * Confirmation email for adding or changing the account email from the profile. Sent to the NEW
   * address; the account keeps its old email (or none) until the code or link is confirmed.
   */
  public void sendEmailChangeConfirmation(String to, String token, String code) {
    try {
      MimeMessage message = mailSender.createMimeMessage();
      MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

      helper.setFrom(fromEmail.trim());
      helper.setTo(to);
      helper.setSubject("Confirm your email address");

      String verifyLink = baseUrl + "/api/v1/auth/verify-email?token=" + token;
      helper.setText(buildEmailChangeEmail(verifyLink, code), true);

      mailSender.send(message);
    } catch (MessagingException e) {
      throw new RuntimeException("Failed to send email", e);
    }
  }

  private String buildEmailChangeEmail(String verifyLink, String code) {
    return "<html>"
        + "<body>"
        + "<h2>Confirm your email address</h2>"
        + "<p>Enter the code below in your EcoPay profile to attach this email address to your"
        + " account:</p>"
        + "<p style=\"font-size:24px;font-weight:bold;letter-spacing:6px;\">"
        + code
        + "</p>"
        + "<p>This code expires in 30 minutes.</p>"
        + "<p>Prefer a link? You can also confirm here: "
        + "<a href=\""
        + verifyLink
        + "\">Confirm Email</a></p>"
        + "<p>If you didn't request this, please ignore this email.</p>"
        + "</body>"
        + "</html>";
  }

  /**
   * Generic transactional-notification email. Used by the notification system for email-eligible
   * events the user hasn't opted out of. {@code link} is an optional frontend-relative path (e.g.
   * {@code /rooms/member/42}) rendered as a "View details" button.
   */
  public void sendNotificationEmail(String to, String subject, String body, String link) {
    try {
      MimeMessage message = mailSender.createMimeMessage();
      MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

      helper.setFrom(fromEmail.trim());
      helper.setTo(to);
      helper.setSubject(subject);

      helper.setText(buildNotificationEmail(subject, body, link), true);

      mailSender.send(message);
    } catch (MessagingException e) {
      throw new RuntimeException("Failed to send email", e);
    }
  }

  private String buildNotificationEmail(String title, String body, String link) {
    StringBuilder html = new StringBuilder();
    html.append("<html><body>")
        .append("<h2>")
        .append(escape(title))
        .append("</h2>")
        .append("<p>")
        .append(escape(body))
        .append("</p>");
    if (link != null && !link.isBlank()) {
      String absolute = link.startsWith("http") ? link : sanitizeFrontendBase(frontendUrl) + link;
      html.append("<p><a href=\"").append(absolute).append("\">View details</a></p>");
    }
    html.append("<hr><p style=\"color:#888;font-size:12px;\">")
        .append("You can manage which emails you receive in EcoPay settings.")
        .append("</p>")
        .append("</body></html>");
    return html.toString();
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

  private String buildPasswordResetEmail(String resetLink) {
    return "<html>"
        + "<body>"
        + "<h2>Password Reset Request</h2>"
        + "<p>Click the link below to reset your password:</p>"
        + "<a href=\""
        + resetLink
        + "\">Reset Password</a>"
        + "<p>This link will expire in 1 hour.</p>"
        + "<p>If you didn't request this, please ignore this email.</p>"
        + "</body>"
        + "</html>";
  }

  private String buildVerificationEmail(String verifyLink, String code) {
    return "<html>"
        + "<body>"
        + "<h2>Verify your email address</h2>"
        + "<p>Thanks for signing up! Enter the code below on the EcoPay registration screen to"
        + " confirm your email and activate your account:</p>"
        + "<p style=\"font-size:24px;font-weight:bold;letter-spacing:6px;\">"
        + code
        + "</p>"
        + "<p>This code expires in 24 hours.</p>"
        + "<p>Prefer a link? You can also confirm here: "
        + "<a href=\""
        + verifyLink
        + "\">Verify Email</a></p>"
        + "<p>If you didn't create an account, please ignore this email.</p>"
        + "</body>"
        + "</html>";
  }
}
