package kz.hrms.splitupauth.dto;

import kz.hrms.splitupauth.entity.ProviderType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceDto {
    private Long id;
    private Long categoryId;
    private String categoryName;
    private String name;
    private String slug;
    private ProviderType providerType;
}