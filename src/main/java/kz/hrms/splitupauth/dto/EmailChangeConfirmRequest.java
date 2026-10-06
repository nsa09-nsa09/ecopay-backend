package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** Add or change the account email: step 2 — confirm with the 6-digit code from the email. */
@Data
public class EmailChangeConfirmRequest {

  @NotBlank(message = "Code is required")
  @Pattern(regexp = "^\\d{6}$", message = "Code must be 6 digits")
  private String code;
}
