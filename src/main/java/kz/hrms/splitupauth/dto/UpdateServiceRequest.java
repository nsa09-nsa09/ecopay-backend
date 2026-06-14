package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.Size;
import kz.hrms.splitupauth.entity.ProviderType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateServiceRequest {

    private Long categoryId;

    @Size(max = 120)
    private String name;

    @Size(max = 120)
    private String slug;

    private ProviderType providerType;

    private Boolean isActive;
}
