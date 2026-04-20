package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.CategoryDto;
import kz.hrms.splitupauth.dto.ServiceDto;
import kz.hrms.splitupauth.dto.TariffPlanDto;
import kz.hrms.splitupauth.entity.Category;
import kz.hrms.splitupauth.entity.ServiceEntity;
import kz.hrms.splitupauth.entity.TariffPlan;
import org.springframework.stereotype.Component;

@Component
public class CatalogMapper {

    public CategoryDto toDto(Category category) {
        return CategoryDto.builder()
                .id(category.getId())
                .name(category.getName())
                .slug(category.getSlug())
                .sortOrder(category.getSortOrder())
                .build();
    }

    public ServiceDto toDto(ServiceEntity service) {
        return ServiceDto.builder()
                .id(service.getId())
                .categoryId(service.getCategory().getId())
                .categoryName(service.getCategory().getName())
                .name(service.getName())
                .slug(service.getSlug())
                .providerType(service.getProviderType())
                .build();
    }

    public TariffPlanDto toDto(TariffPlan tariffPlan) {
        return TariffPlanDto.builder()
                .id(tariffPlan.getId())
                .serviceId(tariffPlan.getService().getId())
                .name(tariffPlan.getName())
                .periodType(tariffPlan.getPeriodType())
                .maxMembers(tariffPlan.getMaxMembers())
                .basePriceTotal(tariffPlan.getBasePriceTotal())
                .currency(tariffPlan.getCurrency())
                .connectionType(tariffPlan.getConnectionType())
                .operatorRules(tariffPlan.getOperatorRules())
                .build();
    }
}