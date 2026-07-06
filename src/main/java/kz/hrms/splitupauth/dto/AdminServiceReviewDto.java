package kz.hrms.splitupauth.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
