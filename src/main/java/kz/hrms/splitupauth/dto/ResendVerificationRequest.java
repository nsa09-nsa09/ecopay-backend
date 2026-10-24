package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import kz.hrms.splitupauth.util.EmailNormalizer;
import lombok.Data;

@Data
public class ResendVerificationRequest {
  @NotBlank(message = "Email is required")
  @Email(message = "Email must be valid")
  private String email;

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
