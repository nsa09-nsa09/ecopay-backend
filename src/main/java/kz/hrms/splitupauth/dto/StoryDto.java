package kz.hrms.splitupauth.dto;

import java.time.LocalDateTime;
import kz.hrms.splitupauth.entity.StoryStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoryDto {
  private Long id;

  private String titleKz;
  private String titleRu;
  private String titleEn;

  private String headingKz;
  private String headingRu;
  private String headingEn;

  private String bodyKz;
  private String bodyRu;
  private String bodyEn;

  private String ctaLabelKz;
  private String ctaLabelRu;
  private String ctaLabelEn;
  private String ctaUrl;

  private String emoji;
  private String gradient;
  private String imageUrl;

  private StoryStatus status;
  private LocalDateTime publishedAt;
  private Integer sortOrder;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
