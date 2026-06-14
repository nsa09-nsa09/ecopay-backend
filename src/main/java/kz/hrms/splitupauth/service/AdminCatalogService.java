package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import kz.hrms.splitupauth.dto.AdminCategoryDto;
import kz.hrms.splitupauth.dto.AdminServiceDto;
import kz.hrms.splitupauth.dto.AdminTariffDto;
import kz.hrms.splitupauth.dto.CreateCategoryRequest;
import kz.hrms.splitupauth.dto.CreateServiceRequest;
import kz.hrms.splitupauth.dto.CreateTariffRequest;
import kz.hrms.splitupauth.dto.UpdateCategoryRequest;
import kz.hrms.splitupauth.dto.UpdateServiceRequest;
import kz.hrms.splitupauth.dto.UpdateTariffRequest;
import kz.hrms.splitupauth.entity.AdminActionLog;
import kz.hrms.splitupauth.entity.AdminActionType;
import kz.hrms.splitupauth.entity.Category;
import kz.hrms.splitupauth.entity.ServiceEntity;
import kz.hrms.splitupauth.entity.TariffPlan;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceConflictException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.CategoryRepository;
import kz.hrms.splitupauth.repository.ServiceRepository;
import kz.hrms.splitupauth.repository.TariffPlanRepository;
import kz.hrms.splitupauth.util.Slugifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminCatalogService {

    private final CategoryRepository categoryRepository;
    private final ServiceRepository serviceRepository;
    private final TariffPlanRepository tariffPlanRepository;
    private final AdminActionLogRepository adminActionLogRepository;
    private final AdminCatalogMapper mapper;
    private final ObjectMapper objectMapper;

    // ===================== Categories =====================

    @Transactional(readOnly = true)
    public List<AdminCategoryDto> listCategories() {
        return categoryRepository.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(c -> mapper.toDto(c, serviceRepository.countByCategoryId(c.getId())))
                .toList();
    }

    @Transactional
    public AdminCategoryDto createCategory(User admin, CreateCategoryRequest req, HttpServletRequest http) {
        String slug = resolveCategorySlug(req.getSlug(), req.getName(), null);

        Category category = Category.builder()
                .name(req.getName())
                .slug(slug)
                .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
                .isActive(req.getIsActive() != null ? req.getIsActive() : true)
                .build();
        category = categoryRepository.save(category);

        ObjectNode newState = objectMapper.createObjectNode();
        newState.put("name", category.getName());
        newState.put("slug", category.getSlug());
        newState.put("isActive", category.getIsActive());
        writeLog(admin, AdminActionType.CATEGORY_CREATED, "CATEGORY", category.getId(),
                null, null, newState, http);

        return mapper.toDto(category, 0L);
    }

    @Transactional
    public AdminCategoryDto updateCategory(Long id, User admin, UpdateCategoryRequest req, HttpServletRequest http) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));

        ObjectNode oldState = objectMapper.createObjectNode();
        oldState.put("name", category.getName());
        oldState.put("slug", category.getSlug());
        oldState.put("isActive", category.getIsActive());
        oldState.put("sortOrder", category.getSortOrder());

        if (req.getName() != null && !req.getName().isBlank()) {
            category.setName(req.getName());
        }
        if (req.getSlug() != null && !req.getSlug().isBlank()) {
            String slug = resolveCategorySlug(req.getSlug(), category.getName(), category.getId());
            category.setSlug(slug);
        }
        if (req.getSortOrder() != null) {
            category.setSortOrder(req.getSortOrder());
        }
        if (req.getIsActive() != null) {
            if (Boolean.FALSE.equals(req.getIsActive())
                    && serviceRepository.existsByCategoryIdAndIsActiveTrue(category.getId())) {
                throw new ResourceConflictException(
                        "Cannot deactivate category: active services exist in this category");
            }
            category.setIsActive(req.getIsActive());
        }

        category = categoryRepository.save(category);

        ObjectNode newState = objectMapper.createObjectNode();
        newState.put("name", category.getName());
        newState.put("slug", category.getSlug());
        newState.put("isActive", category.getIsActive());
        newState.put("sortOrder", category.getSortOrder());
        writeLog(admin, AdminActionType.CATEGORY_UPDATED, "CATEGORY", category.getId(),
                null, oldState, newState, http);

        return mapper.toDto(category, serviceRepository.countByCategoryId(category.getId()));
    }

    @Transactional
    public void deleteCategory(Long id, User admin, HttpServletRequest http) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));

        if (Boolean.FALSE.equals(category.getIsActive())) {
            // Already soft-deleted; nothing to do but keep the call idempotent.
            return;
        }

        if (serviceRepository.existsByCategoryIdAndIsActiveTrue(category.getId())) {
            throw new ResourceConflictException(
                    "Cannot delete category: active services exist in this category");
        }

        ObjectNode oldState = objectMapper.createObjectNode();
        oldState.put("isActive", category.getIsActive());

        category.setIsActive(false);
        categoryRepository.save(category);

        ObjectNode newState = objectMapper.createObjectNode();
        newState.put("isActive", false);
        writeLog(admin, AdminActionType.CATEGORY_DELETED, "CATEGORY", category.getId(),
                null, oldState, newState, http);
    }

    // ===================== Services =====================

    @Transactional(readOnly = true)
    public List<AdminServiceDto> listServices(Long categoryId) {
        List<ServiceEntity> services = (categoryId != null)
                ? serviceRepository.findByCategoryIdOrderByIdAsc(categoryId)
                : serviceRepository.findAllByOrderByIdAsc();
        return services.stream()
                .map(s -> mapper.toDto(s, tariffPlanRepository.countByServiceId(s.getId())))
                .toList();
    }

    @Transactional
    public AdminServiceDto createService(User admin, CreateServiceRequest req, HttpServletRequest http) {
        Category category = categoryRepository.findById(req.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));

        String slug = resolveServiceSlug(req.getSlug(), req.getName(), null);

        ServiceEntity service = ServiceEntity.builder()
                .category(category)
                .name(req.getName())
                .slug(slug)
                .providerType(req.getProviderType())
                .isActive(req.getIsActive() != null ? req.getIsActive() : true)
                .build();
        service = serviceRepository.save(service);

        ObjectNode newState = objectMapper.createObjectNode();
        newState.put("name", service.getName());
        newState.put("slug", service.getSlug());
        newState.put("categoryId", service.getCategory().getId());
        newState.put("providerType", service.getProviderType().name());
        newState.put("isActive", service.getIsActive());
        writeLog(admin, AdminActionType.SERVICE_CREATED, "SERVICE", service.getId(),
                null, null, newState, http);

        return mapper.toDto(service, 0L);
    }

    @Transactional
    public AdminServiceDto updateService(Long id, User admin, UpdateServiceRequest req, HttpServletRequest http) {
        ServiceEntity service = serviceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Service not found"));

        ObjectNode oldState = objectMapper.createObjectNode();
        oldState.put("name", service.getName());
        oldState.put("slug", service.getSlug());
        oldState.put("categoryId", service.getCategory().getId());
        oldState.put("providerType", service.getProviderType().name());
        oldState.put("isActive", service.getIsActive());

        if (req.getCategoryId() != null
                && !req.getCategoryId().equals(service.getCategory().getId())) {
            Category category = categoryRepository.findById(req.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
            service.setCategory(category);
        }
        if (req.getName() != null && !req.getName().isBlank()) {
            service.setName(req.getName());
        }
        if (req.getSlug() != null && !req.getSlug().isBlank()) {
            service.setSlug(resolveServiceSlug(req.getSlug(), service.getName(), service.getId()));
        }
        if (req.getProviderType() != null) {
            service.setProviderType(req.getProviderType());
        }
        if (req.getIsActive() != null) {
            service.setIsActive(req.getIsActive());
        }

        service = serviceRepository.save(service);

        ObjectNode newState = objectMapper.createObjectNode();
        newState.put("name", service.getName());
        newState.put("slug", service.getSlug());
        newState.put("categoryId", service.getCategory().getId());
        newState.put("providerType", service.getProviderType().name());
        newState.put("isActive", service.getIsActive());
        writeLog(admin, AdminActionType.SERVICE_UPDATED, "SERVICE", service.getId(),
                null, oldState, newState, http);

        return mapper.toDto(service, tariffPlanRepository.countByServiceId(service.getId()));
    }

    @Transactional
    public void deleteService(Long id, User admin, HttpServletRequest http) {
        ServiceEntity service = serviceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Service not found"));

        if (Boolean.FALSE.equals(service.getIsActive())) {
            return;
        }

        ObjectNode oldState = objectMapper.createObjectNode();
        oldState.put("isActive", service.getIsActive());

        service.setIsActive(false);
        serviceRepository.save(service);

        ObjectNode newState = objectMapper.createObjectNode();
        newState.put("isActive", false);
        writeLog(admin, AdminActionType.SERVICE_DELETED, "SERVICE", service.getId(),
                null, oldState, newState, http);
    }

    // ===================== Tariffs =====================

    @Transactional(readOnly = true)
    public List<AdminTariffDto> listTariffs(Long serviceId) {
        if (!serviceRepository.existsById(serviceId)) {
            throw new ResourceNotFoundException("Service not found");
        }
        return tariffPlanRepository.findByServiceIdOrderByIdAsc(serviceId).stream()
                .map(mapper::toDto)
                .toList();
    }

    @Transactional
    public AdminTariffDto createTariff(Long serviceId, User admin, CreateTariffRequest req, HttpServletRequest http) {
        ServiceEntity service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Service not found"));

        if (req.getMaxMembers() == null || req.getMaxMembers() < 2) {
            throw new InvalidRequestException("maxMembers must be at least 2");
        }
        if (req.getBasePriceTotal() == null
                || req.getBasePriceTotal().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidRequestException("basePriceTotal must be > 0");
        }

        TariffPlan tariff = TariffPlan.builder()
                .service(service)
                .name(req.getName())
                .periodType(req.getPeriodType())
                .maxMembers(req.getMaxMembers())
                .basePriceTotal(req.getBasePriceTotal())
                .currency(req.getCurrency() != null && !req.getCurrency().isBlank()
                        ? req.getCurrency() : "KZT")
                .connectionType(req.getConnectionType())
                .operatorRules(req.getOperatorRules())
                .features(sanitizeFeatures(req.getFeatures()))
                .isActive(true)
                .build();
        tariff = tariffPlanRepository.save(tariff);

        ObjectNode newState = objectMapper.createObjectNode();
        newState.put("name", tariff.getName());
        newState.put("maxMembers", tariff.getMaxMembers());
        newState.put("basePriceTotal", tariff.getBasePriceTotal().toPlainString());
        newState.put("currency", tariff.getCurrency());
        writeLog(admin, AdminActionType.TARIFF_CREATED, "TARIFF", tariff.getId(),
                null, null, newState, http);

        return mapper.toDto(tariff);
    }

    @Transactional
    public AdminTariffDto updateTariff(Long id, User admin, UpdateTariffRequest req, HttpServletRequest http) {
        TariffPlan tariff = tariffPlanRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tariff not found"));

        ObjectNode oldState = objectMapper.createObjectNode();
        oldState.put("name", tariff.getName());
        oldState.put("maxMembers", tariff.getMaxMembers());
        oldState.put("basePriceTotal", tariff.getBasePriceTotal() != null
                ? tariff.getBasePriceTotal().toPlainString() : null);
        oldState.put("currency", tariff.getCurrency());
        oldState.put("isActive", tariff.getIsActive());

        if (req.getName() != null && !req.getName().isBlank()) {
            tariff.setName(req.getName());
        }
        if (req.getPeriodType() != null) {
            tariff.setPeriodType(req.getPeriodType());
        }
        if (req.getMaxMembers() != null) {
            if (req.getMaxMembers() < 2) {
                throw new InvalidRequestException("maxMembers must be at least 2");
            }
            tariff.setMaxMembers(req.getMaxMembers());
        }
        if (req.getBasePriceTotal() != null) {
            if (req.getBasePriceTotal().compareTo(BigDecimal.ZERO) <= 0) {
                throw new InvalidRequestException("basePriceTotal must be > 0");
            }
            tariff.setBasePriceTotal(req.getBasePriceTotal());
        }
        if (req.getCurrency() != null && !req.getCurrency().isBlank()) {
            tariff.setCurrency(req.getCurrency());
        }
        if (req.getConnectionType() != null) {
            tariff.setConnectionType(req.getConnectionType());
        }
        if (req.getOperatorRules() != null) {
            tariff.setOperatorRules(req.getOperatorRules());
        }
        if (req.getFeatures() != null) {
            tariff.setFeatures(sanitizeFeatures(req.getFeatures()));
        }
        if (req.getIsActive() != null) {
            tariff.setIsActive(req.getIsActive());
        }

        tariff = tariffPlanRepository.save(tariff);

        ObjectNode newState = objectMapper.createObjectNode();
        newState.put("name", tariff.getName());
        newState.put("maxMembers", tariff.getMaxMembers());
        newState.put("basePriceTotal", tariff.getBasePriceTotal().toPlainString());
        newState.put("currency", tariff.getCurrency());
        newState.put("isActive", tariff.getIsActive());
        writeLog(admin, AdminActionType.TARIFF_UPDATED, "TARIFF", tariff.getId(),
                null, oldState, newState, http);

        return mapper.toDto(tariff);
    }

    @Transactional
    public void deleteTariff(Long id, User admin, HttpServletRequest http) {
        TariffPlan tariff = tariffPlanRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tariff not found"));

        if (Boolean.FALSE.equals(tariff.getIsActive())) {
            return;
        }

        ObjectNode oldState = objectMapper.createObjectNode();
        oldState.put("isActive", tariff.getIsActive());

        tariff.setIsActive(false);
        tariffPlanRepository.save(tariff);

        ObjectNode newState = objectMapper.createObjectNode();
        newState.put("isActive", false);
        writeLog(admin, AdminActionType.TARIFF_DELETED, "TARIFF", tariff.getId(),
                null, oldState, newState, http);
    }

    // ===================== Helpers =====================

    private java.util.List<String> sanitizeFeatures(java.util.List<String> features) {
        if (features == null) {
            return new java.util.ArrayList<>();
        }
        java.util.List<String> out = new java.util.ArrayList<>(features.size());
        for (String f : features) {
            if (f == null) continue;
            String clean = kz.hrms.splitupauth.util.TextSanitizer.sanitize(f);
            if (clean != null && !clean.isBlank()) {
                out.add(clean);
            }
        }
        return out;
    }

    private String resolveCategorySlug(String requested, String name, Long ignoreId) {
        String base = requested != null && !requested.isBlank()
                ? Slugifier.slugify(requested) : Slugifier.slugify(name);
        if (base.isEmpty()) {
            base = "category";
        }
        String candidate = base;
        int suffix = 2;
        while (collidesCategorySlug(candidate, ignoreId)) {
            candidate = Slugifier.appendSuffix(base, suffix++);
        }
        return candidate;
    }

    private boolean collidesCategorySlug(String slug, Long ignoreId) {
        return categoryRepository.findBySlug(slug)
                .filter(c -> ignoreId == null || !c.getId().equals(ignoreId))
                .isPresent();
    }

    private String resolveServiceSlug(String requested, String name, Long ignoreId) {
        String base = requested != null && !requested.isBlank()
                ? Slugifier.slugify(requested) : Slugifier.slugify(name);
        if (base.isEmpty()) {
            base = "service";
        }
        String candidate = base;
        int suffix = 2;
        while (collidesServiceSlug(candidate, ignoreId)) {
            candidate = Slugifier.appendSuffix(base, suffix++);
        }
        return candidate;
    }

    private boolean collidesServiceSlug(String slug, Long ignoreId) {
        return serviceRepository.findBySlug(slug)
                .filter(s -> ignoreId == null || !s.getId().equals(ignoreId))
                .isPresent();
    }

    private void writeLog(User admin,
                          AdminActionType type,
                          String entityType,
                          Long entityId,
                          String reason,
                          ObjectNode oldState,
                          ObjectNode newState,
                          HttpServletRequest http) {
        adminActionLogRepository.save(AdminActionLog.builder()
                .eventId(UUID.randomUUID())
                .adminUser(admin)
                .actionType(type)
                .entityType(entityType)
                .entityId(entityId)
                .reason(reason)
                .oldState(oldState)
                .newState(newState)
                .ipAddress(http != null ? http.getRemoteAddr() : null)
                .userAgent(http != null ? http.getHeader("User-Agent") : null)
                .build());
    }
}
