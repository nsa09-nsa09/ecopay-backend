package kz.hrms.splitupauth.dto;

import kz.hrms.splitupauth.entity.Feedback;
import kz.hrms.splitupauth.entity.FeedbackStatus;
import kz.hrms.splitupauth.entity.FeedbackType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Admin-side view: includes who submitted it (id/email/displayName/publicId),
 * the admin note, and which admin handled the most recent triage action.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminFeedbackDto {
    private Long id;
    private FeedbackType type;
    private String subject;
    private String message;
    private FeedbackStatus status;
    private String adminNote;

    private Long userId;
    private String userEmail;
    private String userDisplayName;
    private String userPublicId;

    private Long handledByUserId;
    private String handledByDisplayName;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static AdminFeedbackDto from(Feedback f) {
        return AdminFeedbackDto.builder()
                .id(f.getId())
                .type(f.getType())
                .subject(f.getSubject())
                .message(f.getMessage())
                .status(f.getStatus())
                .adminNote(f.getAdminNote())
                .userId(f.getUser() != null ? f.getUser().getId() : null)
                .userEmail(f.getUser() != null ? f.getUser().getEmail() : null)
                .userDisplayName(f.getUser() != null ? f.getUser().getDisplayName() : null)
                .userPublicId(f.getUser() != null ? f.getUser().getPublicId() : null)
                .handledByUserId(f.getHandledBy() != null ? f.getHandledBy().getId() : null)
                .handledByDisplayName(f.getHandledBy() != null ? f.getHandledBy().getDisplayName() : null)
                .createdAt(f.getCreatedAt())
                .updatedAt(f.getUpdatedAt())
                .build();
    }
}
