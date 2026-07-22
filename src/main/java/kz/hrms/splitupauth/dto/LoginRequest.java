package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import kz.hrms.splitupauth.util.EmailNormalizer;
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
