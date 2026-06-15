package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kz.hrms.splitupauth.entity.FeedbackType;
import lombok.Data;

@Data
public class CreateFeedbackRequest {

    @NotNull(message = "Feedback type is required")
    private FeedbackType type;

    @Size(max = 150, message = "Subject must be at most 150 characters")
    private String subject;

    @NotBlank(message = "Message is required")
    @Size(min = 5, max = 4000, message = "Message must be 5..4000 characters")
    private String message;
}
