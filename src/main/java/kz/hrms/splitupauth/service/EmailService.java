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

            String resetLink = frontendUrl + "/reset-password/confirm?token=" + token;
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
        return "<html>" +
                "<body>" +
                "<h2>Sign-in verification code</h2>" +
                "<p>Use the code below to finish signing in to the EcoPay staff console:</p>" +
                "<p style=\"font-size:24px;font-weight:bold;letter-spacing:6px;\">" + code + "</p>" +
                "<p>This code will expire in a few minutes. If you did not try to sign in, " +
                "please change your password immediately.</p>" +
                "</body>" +
                "</html>";
    }

    public void sendVerificationEmail(String to, String token) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail.trim());
            helper.setTo(to);
            helper.setSubject("Verify your email address");

            String verifyLink = baseUrl + "/api/v1/auth/verify-email?token=" + token;
            String htmlContent = buildVerificationEmail(verifyLink);

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

    private String buildVerificationEmail(String verifyLink) {
        return "<html>" +
                "<body>" +
                "<h2>Verify your email address</h2>" +
                "<p>Thanks for signing up! Click the link below to confirm your email and activate your account:</p>" +
                "<a href=\"" + verifyLink + "\">Verify Email</a>" +
                "<p>This link will expire in 24 hours.</p>" +
                "<p>If you didn't create an account, please ignore this email.</p>" +
                "</body>" +
                "</html>";
    }
}
