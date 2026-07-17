package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class LoginRequest {

  /** Optional: exactly one of {@link #email} / {@link #phone} identifies the account. */
  @Email(message = "Email must be valid")
  private String email;

  /** Optional: see {@link #email}. */
  @Pattern(regexp = "^\\+7\\d{10}$", message = "Phone must be in +7XXXXXXXXXX format")
  private String phone;

  @NotBlank(message = "Password is required")
  private String password;

  @AssertTrue(message = "Exactly one of email or phone is required")
  public boolean isIdentifierPresent() {
    boolean hasEmail = email != null && !email.isBlank();
    boolean hasPhone = phone != null && !phone.isBlank();
    return hasEmail ^ hasPhone;
  }

  /** The identifier the caller actually supplied — used for rate-limit keying and lookups. */
  public String identifier() {
    return email != null && !email.isBlank() ? email : phone;
  }
}
