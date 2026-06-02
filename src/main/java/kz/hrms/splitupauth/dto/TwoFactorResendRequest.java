package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TwoFactorResendRequest {

    @NotBlank(message = "Challenge id is required")
    @Size(max = 64)
    private String challengeId;
}
