package kz.hrms.splitupauth.dto;

import java.time.LocalDateTime;
import kz.hrms.splitupauth.entity.ProviderType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminServiceDto {
  private Long id;
  private Long categoryId;
  private String categoryName;
  private String name;
  private String slug;
  private ProviderType providerType;
  private Boolean isActive;
  private Long tariffsCount;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  /** Backend-served URL for the service logo, or null when none uploaded. */
  private String logoUrl;
}
