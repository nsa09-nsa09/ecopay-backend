package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.AdminCategoryDto;
import kz.hrms.splitupauth.dto.AdminServiceDto;
import kz.hrms.splitupauth.dto.AdminTariffDto;
import kz.hrms.splitupauth.entity.Category;
import kz.hrms.splitupauth.entity.ServiceEntity;
import kz.hrms.splitupauth.entity.TariffPlan;
import org.springframework.stereotype.Component;

@Component
public class AdminCatalogMapper {

    public AdminCategoryDto toDto(Category category, long servicesCount) {
        return AdminCategoryDto.builder()
                .id(category.getId())
                .name(category.getName())
                .slug(category.getSlug())
                .isActive(category.getIsActive())
                .sortOrder(category.getSortOrder())
                .servicesCount(servicesCount)
                .createdAt(category.getCreatedAt())
                .updatedAt(category.getUpdatedAt())
                .build();
    }

    public AdminServiceDto toDto(ServiceEntity service, long tariffsCount) {
        return AdminServiceDto.builder()
                .id(service.getId())
                .categoryId(service.getCategory().getId())
                .categoryName(service.getCategory().getName())
                .name(service.getName())
                .slug(service.getSlug())
                .providerType(service.getProviderType())
                .isActive(service.getIsActive())
                .tariffsCount(tariffsCount)
                .createdAt(service.getCreatedAt())
                .updatedAt(service.getUpdatedAt())
                .build();
    }

    public AdminTariffDto toDto(TariffPlan tariff) {
        return AdminTariffDto.builder()
                .id(tariff.getId())
                .serviceId(tariff.getService().getId())
                .name(tariff.getName())
                .periodType(tariff.getPeriodType())
                .maxMembers(tariff.getMaxMembers())
                .basePriceTotal(tariff.getBasePriceTotal())
                .currency(tariff.getCurrency())
                .connectionType(tariff.getConnectionType())
                .operatorRules(tariff.getOperatorRules())
                .features(tariff.getFeatures() == null
                        ? java.util.List.of() : java.util.List.copyOf(tariff.getFeatures()))
                .isActive(tariff.getIsActive())
                .createdAt(tariff.getCreatedAt())
                .updatedAt(tariff.getUpdatedAt())
                .build();
    }
}
