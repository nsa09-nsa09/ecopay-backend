package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Add or change the account email: step 1 — request a confirmation code for the new address. */
@Data
public class EmailChangeRequest {

  @NotBlank(message = "Email is required")
  @Email(message = "Email must be valid")
  private String email;
}
