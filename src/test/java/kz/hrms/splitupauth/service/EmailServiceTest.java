package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

  @Mock private JavaMailSender mailSender;

  private EmailService emailService;

  @BeforeEach
  void setUp() {
    emailService = new EmailService(mailSender);
    ReflectionTestUtils.setField(emailService, "fromEmail", "no-reply@ecopay.kz");
    ReflectionTestUtils.setField(emailService, "brandName", "EcoPay");
    ReflectionTestUtils.setField(emailService, "maxAttempts", 1);
    ReflectionTestUtils.setField(emailService, "retryBackoffMs", 1L);
  }

  @Test
  void passwordResetEmailUsesConfiguredFrontendUrl() throws Exception {
    ReflectionTestUtils.setField(emailService, "frontendUrl", "https://app.ecopay.kz/");
    ReflectionTestUtils.setField(emailService, "brandPublicUrl", "");
    MimeMessage message = newMessage();
    when(mailSender.createMimeMessage()).thenReturn(message);

    emailService.sendPasswordResetEmail("user@example.com", "reset-token-123", MailLocale.EN);

    String html = sentHtml();
    assertTrue(html.contains("https://app.ecopay.kz/reset-password/confirm?token=reset-token-123"));
    assertFalse(html.contains("localhost"));
  }

  @Test
  void passwordResetEmailFallsBackToBrandPublicUrlWhenFrontendUrlIsLocalhost() throws Exception {
    ReflectionTestUtils.setField(emailService, "frontendUrl", "http://localhost:5173");
    ReflectionTestUtils.setField(emailService, "brandPublicUrl", "https://ecopay.kz/");
    MimeMessage message = newMessage();
    when(mailSender.createMimeMessage()).thenReturn(message);

    emailService.sendPasswordResetEmail("user@example.com", "reset-token-456", MailLocale.EN);

    String html = sentHtml();
    assertTrue(html.contains("https://ecopay.kz/reset-password/confirm?token=reset-token-456"));
    assertFalse(html.contains("localhost"));
  }

  @Test
  void notificationEmailPreservesLocalhostPortWhenNoBrandUrl() throws Exception {
    ReflectionTestUtils.setField(emailService, "frontendUrl", "http://localhost:5173");
    ReflectionTestUtils.setField(emailService, "brandPublicUrl", "");
    MimeMessage message = newMessage();
    when(mailSender.createMimeMessage()).thenReturn(message);

    emailService.sendNotificationEmail(
        "user@example.com", "Заявка отправлена", "Ваша заявка отправлена", "/rooms/member/123", MailLocale.RU);

    String html = sentHtml();
    assertTrue(html.contains("http://localhost:5173/rooms/member/123"));
    assertFalse(html.contains("http://localhost/rooms/"));
  }

  @Test
  void notificationEmailUsesExplicitFrontendBaseFromRequest() throws Exception {
    ReflectionTestUtils.setField(emailService, "frontendUrl", "http://localhost:5173");
    ReflectionTestUtils.setField(emailService, "brandPublicUrl", "");
    MimeMessage message = newMessage();
    when(mailSender.createMimeMessage()).thenReturn(message);

    emailService.sendNotificationEmail(
        "user@example.com",
        "Участие активно",
        "Активно",
        "/rooms/member/456",
        MailLocale.RU,
        "https://stage.ecopay.kz/");

    String html = sentHtml();
    assertTrue(html.contains("https://stage.ecopay.kz/rooms/member/456"));
    assertFalse(html.contains("localhost"));
  }

  @Test
  void notificationEmailUsesPublicFrontendUrl() throws Exception {
    ReflectionTestUtils.setField(emailService, "frontendUrl", "https://app.ecopay.kz");
    ReflectionTestUtils.setField(emailService, "brandPublicUrl", "");
    MimeMessage message = newMessage();
    when(mailSender.createMimeMessage()).thenReturn(message);

    emailService.sendNotificationEmail(
        "user@example.com", "Оплата подтверждена", "Оплата прошла", "/rooms/member/789", MailLocale.RU);

    String html = sentHtml();
    assertTrue(html.contains("https://app.ecopay.kz/rooms/member/789"));
  }

  private MimeMessage newMessage() {
    return new MimeMessage(Session.getInstance(new Properties()));
  }

  private String sentHtml() throws Exception {
    ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
    verify(mailSender).send(captor.capture());
    return extractText(captor.getValue().getContent());
  }

  private String extractText(Object content) throws Exception {
    if (content instanceof String text) {
      return text;
    }
    if (content instanceof Multipart multipart) {
      StringBuilder out = new StringBuilder();
      for (int i = 0; i < multipart.getCount(); i++) {
        BodyPart part = multipart.getBodyPart(i);
        out.append(extractText(part.getContent()));
      }
      return out.toString();
    }
    return String.valueOf(content);
  }
}
