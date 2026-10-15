package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kz.hrms.splitupauth.util.EmailNormalizer;
import lombok.Data;

/**
 * B14: payload for the unauthenticated POST /auth/ban-appeal — a banned user signs in with email +
 * password and attaches a 20–2000 character message to be reviewed by support.
 */
@Data
public class BanAppealRequest {

  @NotBlank(message = "Укажите email")
  @Email(message = "Укажите корректный email")
  private String email;

  @NotBlank(message = "Укажите пароль")
  private String password;

  @NotBlank(message = "Опишите, почему блокировка должна быть снята")
  @Size(min = 20, max = 2000, message = "Сообщение должно быть от 20 до 2000 символов")
  private String message;

  public void setEmail(String email) {
    this.email = EmailNormalizer.normalize(email);
  }
}
