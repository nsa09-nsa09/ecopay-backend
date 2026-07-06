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
public class PublicServiceReviewDto {
  private Long id;
  private Integer rating;
  private String text;
  private String authorDisplayName;
  private String authorPublicId;
  private LocalDateTime createdAt;
}
