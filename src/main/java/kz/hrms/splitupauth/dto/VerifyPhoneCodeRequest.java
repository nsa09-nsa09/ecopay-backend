package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * Final step of phone registration: the caller submits their phone plus the 6-digit SMS code. On
 * success the account's phone is verified and tokens are issued (mirrors VerifyEmailCodeRequest).
 */
@Data
public class VerifyPhoneCodeRequest {

  @NotBlank(message = "Phone is required")
  @Pattern(regexp = "^\\+7\\d{10}$", message = "Phone must be in +7XXXXXXXXXX format")
  private String phone;

  @NotBlank(message = "Code is required")
  @Pattern(regexp = "^\\d{6}$", message = "Code must be 6 digits")
  private String code;
}
