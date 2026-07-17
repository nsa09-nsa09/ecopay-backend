package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

  /**
   * Optional: exactly one of {@link #email} / {@link #phone} identifies the new account. Email
   * registration confirms via an emailed code; phone registration confirms via an SMS code.
   */
  @Email(message = "Email must be valid")
  private String email;

  /** Optional: see {@link #email}. Phone-registered accounts add an email later in the profile. */
  @Pattern(regexp = "^\\+7\\d{10}$", message = "Phone must be in +7XXXXXXXXXX format")
  private String phone;

  @NotBlank(message = "Password is required")
  @Size(min = 8, message = "Password must be at least 8 characters")
  private String password;

  @NotBlank(message = "Display name is required")
  private String displayName;

  /**
   * Registration is gated on the user ticking "I accept the Terms of Service and consent to
   * personal data processing". Anything other than {@code true} returns 400 before
   * AuthService.register is reached.
   *
   * <p>{@code @AssertTrue} alone is NOT enough: per the Bean Validation spec it treats {@code null}
   * as valid, so a payload that simply omits the field would slip through. {@code @NotNull} closes
   * that gap; {@code @AssertTrue} then rejects an explicit {@code false}.
   */
  @NotNull(message = "You must accept the Terms of Service")
  @AssertTrue(message = "You must accept the Terms of Service")
  private Boolean termsAccepted;

  /** Optional: version of Terms of Service the user saw when accepting. */
  private Integer acceptedTermsVersion;

  /** Optional: version of Privacy consent the user saw when accepting. */
  private Integer acceptedPrivacyVersion;

  @AssertTrue(message = "Exactly one of email or phone is required")
  public boolean isIdentifierPresent() {
    boolean hasEmail = email != null && !email.isBlank();
    boolean hasPhone = phone != null && !phone.isBlank();
    return hasEmail ^ hasPhone;
  }
}
