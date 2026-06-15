package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.Size;
import kz.hrms.splitupauth.entity.FeedbackStatus;
import lombok.Data;

/**
 * Admin PATCH body. Either or both fields may be present; absent fields stay
 * untouched. The service requires at least one to be set.
 */
@Data
public class UpdateFeedbackRequest {

    private FeedbackStatus status;

    @Size(max = 4000, message = "Admin note must be at most 4000 characters")
    private String adminNote;
}
