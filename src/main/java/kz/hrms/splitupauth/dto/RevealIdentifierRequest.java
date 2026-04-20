package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RevealIdentifierRequest {

    @NotBlank(message = "Reason is required")
    @Size(max = 255, message = "Reason must be at most 255 characters")
    private String reason;

    @Size(max = 30, message = "Context type must be at most 30 characters")
    private String contextType;

    private Long contextId;
}
