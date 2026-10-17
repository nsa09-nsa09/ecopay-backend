package kz.hrms.splitupauth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryDto {
  private Long id;
  private String name;

  /** B18: per-locale names; null falls back to {@link #name} on the frontend. */
  private String nameRu;

  private String nameKz;
  private String nameEn;

  private String slug;
  private Integer sortOrder;
}
