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
