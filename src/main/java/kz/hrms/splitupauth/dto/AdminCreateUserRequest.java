package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.util.EmailNormalizer;
import lombok.Data;

@Data
public class AdminCreateUserRequest {

  @NotBlank(message = "Укажите email")
  @Email(message = "Укажите корректный email")
  private String email;

  @NotBlank(message = "Укажите имя для отображения")
  @Size(min = 2, max = 50, message = "Имя должно быть от 2 до 50 символов")
  private String displayName;

  @NotBlank(message = "Укажите пароль")
  @Size(min = 8, max = 100, message = "Пароль должен быть от 8 до 100 символов")
  private String password;

  @NotNull(message = "Выберите роль")
  private Role role;

  // Phone is optional for admin-created accounts; staff/admin profiles may not have one.
  @Pattern(regexp = "^\\+7\\d{10}$", message = "Телефон должен быть в формате +7XXXXXXXXXX")
  private String phone;

  /**
   * Canonicalises on bind, before Bean Validation runs. Order matters: {@code @Email} rejects
   * {@code " user@gmail.com "} outright, so without this a pasted address with a trailing space —
   * common, since copying out of another app drags whitespace along — is turned away with an
   * unhelpful "Email must be valid" and the service-layer normalizer never sees it.
   *
   * <p>Written by hand rather than as a Jackson annotation on purpose: the HTTP layer runs Jackson
   * 3 ({@code tools.jackson}) while parts of this project still use the 2.x namespace, and a setter
   * is correct under both.
   */
  public void setEmail(String email) {
    this.email = EmailNormalizer.normalize(email);
  }
}
