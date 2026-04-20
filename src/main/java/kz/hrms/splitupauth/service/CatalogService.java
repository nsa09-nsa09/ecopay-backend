package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.CategoryDto;
import kz.hrms.splitupauth.dto.ServiceDto;
import kz.hrms.splitupauth.dto.TariffPlanDto;
import kz.hrms.splitupauth.entity.ServiceEntity;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.CategoryRepository;
import kz.hrms.splitupauth.repository.ServiceRepository;
import kz.hrms.splitupauth.repository.TariffPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CatalogService {

    private final CategoryRepository categoryRepository;
    private final ServiceRepository serviceRepository;
    private final TariffPlanRepository tariffPlanRepository;
    private final CatalogMapper catalogMapper;

    @Transactional(readOnly = true)
    public List<CategoryDto> getCategories() {
        return categoryRepository.findByIsActiveTrueOrderBySortOrderAscIdAsc()
                .stream()
                .map(catalogMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ServiceDto> getServices(Long categoryId) {
        if (categoryId != null) {
            return serviceRepository.findByCategoryIdAndIsActiveTrueOrderByIdAsc(categoryId)
                    .stream()
                    .map(catalogMapper::toDto)
                    .toList();
        }

        return serviceRepository.findByIsActiveTrueOrderByIdAsc()
                .stream()
                .map(catalogMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public ServiceDto getService(Long id) {
        ServiceEntity service = serviceRepository.findById(id)
                .filter(ServiceEntity::getIsActive)
                .orElseThrow(() -> new ResourceNotFoundException("Service not found"));

        return catalogMapper.toDto(service);
    }

    @Transactional(readOnly = true)
    public List<TariffPlanDto> getTariffs(Long serviceId) {
        return tariffPlanRepository.findByServiceIdAndIsActiveTrueOrderByIdAsc(serviceId)
                .stream()
                .map(catalogMapper::toDto)
                .toList();
    }
}