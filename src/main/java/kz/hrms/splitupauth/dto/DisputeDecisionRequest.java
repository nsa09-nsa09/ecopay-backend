package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DisputeDecisionRequest {

    @NotBlank(message = "Status is required")
    private String status;

    @NotBlank(message = "Decision is required")
    @Size(max = 50, message = "Decision must be at most 50 characters")
    private String decision;

    @NotBlank(message = "Comment is required")
    @Size(max = 1000, message = "Comment must be at most 1000 characters")
    private String comment;
}