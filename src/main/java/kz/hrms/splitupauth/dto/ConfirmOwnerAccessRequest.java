package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ConfirmOwnerAccessRequest {

    @NotBlank(message = "Access method is required")
    @Size(max = 50, message = "Access method must be at most 50 characters")
    private String accessMethod;
}