package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kz.hrms.splitupauth.util.EmailNormalizer;
import lombok.Data;

@Data
public class RegisterRequest {

  /** Required: EcoPay accounts are registered by email and confirmed via an emailed code. */
  @NotBlank(message = "Email is required")
  @Email(message = "Email must be valid")
  private String email;

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
