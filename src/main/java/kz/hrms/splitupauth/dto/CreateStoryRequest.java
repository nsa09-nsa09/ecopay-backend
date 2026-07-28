package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import kz.hrms.splitupauth.entity.StoryStatus;
import lombok.Data;

@Data
public class CreateStoryRequest {

  @Size(max = 255)
  private String titleKz;

  @Size(max = 255)
  private String titleRu;

  @Size(max = 255)
  private String titleEn;

  @Size(max = 255)
  private String headingKz;

  @Size(max = 255)
  private String headingRu;

  @Size(max = 255)
  private String headingEn;

  @Size(max = 20000)
  private String bodyKz;

  @Size(max = 20000)
  private String bodyRu;

  @Size(max = 20000)
  private String bodyEn;

  @Size(max = 120)
  private String ctaLabelKz;

  @Size(max = 120)
  private String ctaLabelRu;

  @Size(max = 120)
  private String ctaLabelEn;

  @Size(max = 500)
  private String ctaUrl;

  @Size(max = 32)
  private String emoji;

  @Size(max = 255)
  private String gradient;

  private StoryStatus status;

  @PositiveOrZero private Integer sortOrder;
}
