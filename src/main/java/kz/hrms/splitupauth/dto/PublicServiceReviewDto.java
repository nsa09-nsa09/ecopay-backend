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
public class PublicServiceReviewDto {
    private Long id;
    private Integer rating;
    private String text;
    private String authorDisplayName;
    private String authorPublicId;
    private LocalDateTime createdAt;
}
