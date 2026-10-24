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
public class AdminCategoryDto {
  private Long id;
  private String name;
  private String slug;
  private Boolean isActive;
  private Integer sortOrder;
  private Long servicesCount;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
