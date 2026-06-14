package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateCategoryRequest {

    @Size(max = 100)
    private String name;

    @Size(max = 120)
    private String slug;

    private Integer sortOrder;

    private Boolean isActive;
}
