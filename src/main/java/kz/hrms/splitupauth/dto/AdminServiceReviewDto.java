package kz.hrms.splitupauth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminServiceReviewDto {
    private Long id;
    private Long authorId;
    private String authorPublicId;
    private String authorDisplayName;
    private String authorEmail;
    private Integer rating;
    private String text;
    private Boolean featured;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
