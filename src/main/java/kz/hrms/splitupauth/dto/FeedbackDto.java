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
 * User-facing feedback view — exposes only what the submitter should see about
 * their own row (no admin note, no handled_by).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackDto {
    private Long id;
    private FeedbackType type;
    private String subject;
    private String message;
    private FeedbackStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static FeedbackDto from(Feedback f) {
        return FeedbackDto.builder()
                .id(f.getId())
                .type(f.getType())
                .subject(f.getSubject())
                .message(f.getMessage())
                .status(f.getStatus())
                .createdAt(f.getCreatedAt())
                .updatedAt(f.getUpdatedAt())
                .build();
    }
}
