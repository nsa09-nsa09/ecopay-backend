package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TwoFactorVerifyRequest {

    @NotBlank(message = "Challenge id is required")
    @Size(max = 64)
    private String challengeId;

    @NotBlank(message = "Code is required")
    @Pattern(regexp = "\\d{6}", message = "Code must be 6 digits")
    private String code;
}
