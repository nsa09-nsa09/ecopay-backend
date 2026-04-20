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
    public void sendPasswordResetEmail(String to, String token) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail.trim());
            helper.setTo(to);
            helper.setSubject("Password Reset Request");

            String resetLink = "http://localhost:8080/api/v1/auth/reset-password/confirm?token=" + token;
            String htmlContent = buildPasswordResetEmail(resetLink);

            helper.setText(htmlContent, true);

            mailSender.send(message);
        } catch (MessagingException e) {
            throw new RuntimeException("Failed to send email", e);
        }
    }

    private String buildPasswordResetEmail(String resetLink) {
        return "<html>" +
                "<body>" +
                "<h2>Password Reset Request</h2>" +
                "<p>Click the link below to reset your password:</p>" +
                "<a href=\"" + resetLink + "\">Reset Password</a>" +
                "<p>This link will expire in 1 hour.</p>" +
                "<p>If you didn't request this, please ignore this email.</p>" +
                "</body>" +
                "</html>";
    }
}
