package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import kz.hrms.splitupauth.dto.AdminCategoryDto;
import kz.hrms.splitupauth.dto.AdminServiceDto;
import kz.hrms.splitupauth.dto.AdminTariffDto;
import kz.hrms.splitupauth.dto.CreateCategoryRequest;
import kz.hrms.splitupauth.dto.CreateServiceRequest;
import kz.hrms.splitupauth.dto.CreateTariffRequest;
import kz.hrms.splitupauth.dto.UpdateCategoryRequest;
import kz.hrms.splitupauth.entity.AdminActionLog;
import kz.hrms.splitupauth.entity.AdminActionType;
import kz.hrms.splitupauth.entity.Category;
import kz.hrms.splitupauth.entity.PeriodType;
import kz.hrms.splitupauth.entity.ProviderType;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.ServiceEntity;
import kz.hrms.splitupauth.entity.TariffPlan;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceConflictException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.CategoryRepository;
import kz.hrms.splitupauth.repository.ServiceRepository;
import kz.hrms.splitupauth.repository.TariffPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminCatalogServiceTest {

    @Mock private CategoryRepository categoryRepository;
    @Mock private ServiceRepository serviceRepository;
    @Mock private TariffPlanRepository tariffPlanRepository;
    @Mock private AdminActionLogRepository adminActionLogRepository;
    @Mock private HttpServletRequest http;

    private AdminCatalogMapper mapper;
    private AdminCatalogService service;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final User admin = User.builder().id(99L).email("a@e.kz").role(Role.ADMIN)
            .status(UserStatus.ACTIVE).build();

    @BeforeEach
    void setUp() {
        mapper = new AdminCatalogMapper();
        service = new AdminCatalogService(
                categoryRepository, serviceRepository, tariffPlanRepository,
                adminActionLogRepository, mapper, objectMapper);
    }

    // ===================== Categories =====================

    @Test
    void createCategory_persistsAndLogs_andAutoGeneratesUniqueSlug() {
        CreateCategoryRequest req = new CreateCategoryRequest();
        req.setName("Видео");
        // Simulate collision on the obvious slug → must retry with suffix.
        when(categoryRepository.findBySlug("video"))
                .thenReturn(Optional.of(Category.builder().id(50L).build()));
        when(categoryRepository.findBySlug("video-2")).thenReturn(Optional.empty());
        when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> {
            Category c = inv.getArgument(0);
            c.setId(123L);
            return c;
        });

        AdminCategoryDto dto = service.createCategory(admin, req, http);

        ArgumentCaptor<Category> cap = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository).save(cap.capture());
        assertEquals("video-2", cap.getValue().getSlug());
        assertEquals("Видео", cap.getValue().getName());
        assertEquals(123L, dto.getId());

        ArgumentCaptor<AdminActionLog> logCap = ArgumentCaptor.forClass(AdminActionLog.class);
        verify(adminActionLogRepository).save(logCap.capture());
        assertEquals(AdminActionType.CATEGORY_CREATED, logCap.getValue().getActionType());
        assertEquals("CATEGORY", logCap.getValue().getEntityType());
        assertEquals(123L, logCap.getValue().getEntityId());
    }

    @Test
    void updateCategory_deactivate_rejectsWhenActiveServicesExist() {
        Category existing = Category.builder().id(7L).name("Old").slug("old").isActive(true).sortOrder(0).build();
        when(categoryRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(serviceRepository.existsByCategoryIdAndIsActiveTrue(7L)).thenReturn(true);

        UpdateCategoryRequest req = new UpdateCategoryRequest();
        req.setIsActive(false);

        assertThrows(ResourceConflictException.class,
                () -> service.updateCategory(7L, admin, req, http));
        verify(categoryRepository, never()).save(any());
        verify(adminActionLogRepository, never()).save(any());
    }

    @Test
    void deleteCategory_softDeactivates_andLogs() {
        Category existing = Category.builder().id(8L).name("Music").slug("music").isActive(true).sortOrder(0).build();
        when(categoryRepository.findById(8L)).thenReturn(Optional.of(existing));
        when(serviceRepository.existsByCategoryIdAndIsActiveTrue(8L)).thenReturn(false);

        service.deleteCategory(8L, admin, http);

        assertFalse(existing.getIsActive());
        verify(categoryRepository).save(existing);
        ArgumentCaptor<AdminActionLog> logCap = ArgumentCaptor.forClass(AdminActionLog.class);
        verify(adminActionLogRepository).save(logCap.capture());
        assertEquals(AdminActionType.CATEGORY_DELETED, logCap.getValue().getActionType());
    }

    @Test
    void deleteCategory_rejectsWhenActiveServicesExist() {
        Category existing = Category.builder().id(9L).name("X").slug("x").isActive(true).sortOrder(0).build();
        when(categoryRepository.findById(9L)).thenReturn(Optional.of(existing));
        when(serviceRepository.existsByCategoryIdAndIsActiveTrue(9L)).thenReturn(true);

        assertThrows(ResourceConflictException.class,
                () -> service.deleteCategory(9L, admin, http));
        verify(categoryRepository, never()).save(any());
    }

    // ===================== Services =====================

    @Test
    void createService_attachesCategory_andLogs() {
        Category category = Category.builder().id(2L).name("Video").slug("video").isActive(true).build();
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(category));
        when(serviceRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(serviceRepository.save(any(ServiceEntity.class))).thenAnswer(inv -> {
            ServiceEntity s = inv.getArgument(0);
            s.setId(55L);
            return s;
        });

        CreateServiceRequest req = new CreateServiceRequest();
        req.setCategoryId(2L);
        req.setName("YouTube Premium");
        req.setProviderType(ProviderType.DIGITAL);

        AdminServiceDto dto = service.createService(admin, req, http);

        assertEquals(55L, dto.getId());
        assertNotNull(dto.getSlug());
        assertTrue(dto.getSlug().startsWith("youtube"));

        ArgumentCaptor<AdminActionLog> logCap = ArgumentCaptor.forClass(AdminActionLog.class);
        verify(adminActionLogRepository).save(logCap.capture());
        assertEquals(AdminActionType.SERVICE_CREATED, logCap.getValue().getActionType());
    }

    @Test
    void deleteService_softDeactivates_andLogs() {
        Category c = Category.builder().id(1L).name("C").slug("c").build();
        ServiceEntity s = ServiceEntity.builder().id(40L).category(c).name("N").slug("n")
                .providerType(ProviderType.DIGITAL).isActive(true).build();
        when(serviceRepository.findById(40L)).thenReturn(Optional.of(s));

        service.deleteService(40L, admin, http);

        assertFalse(s.getIsActive());
        verify(serviceRepository).save(s);
        verify(adminActionLogRepository, times(1)).save(any());
    }

    // ===================== Tariffs =====================

    @Test
    void createTariff_validatesMaxMembersAndPrice() {
        ServiceEntity svc = ServiceEntity.builder().id(70L).build();
        when(serviceRepository.findById(70L)).thenReturn(Optional.of(svc));

        CreateTariffRequest req = new CreateTariffRequest();
        req.setName("YT 4-pack");
        req.setPeriodType(PeriodType.MONTHLY);
        req.setMaxMembers(1);
        req.setBasePriceTotal(new BigDecimal("100.00"));

        assertThrows(InvalidRequestException.class,
                () -> service.createTariff(70L, admin, req, http));

        req.setMaxMembers(4);
        req.setBasePriceTotal(BigDecimal.ZERO);
        assertThrows(InvalidRequestException.class,
                () -> service.createTariff(70L, admin, req, http));
    }

    @Test
    void createTariff_succeedsAndLogs_withDefaultCurrencyKZT() {
        ServiceEntity svc = ServiceEntity.builder().id(70L).build();
        when(serviceRepository.findById(70L)).thenReturn(Optional.of(svc));
        when(tariffPlanRepository.save(any(TariffPlan.class))).thenAnswer(inv -> {
            TariffPlan t = inv.getArgument(0);
            t.setId(900L);
            return t;
        });

        CreateTariffRequest req = new CreateTariffRequest();
        req.setName("YT 4-pack");
        req.setPeriodType(PeriodType.MONTHLY);
        req.setMaxMembers(4);
        req.setBasePriceTotal(new BigDecimal("3160.00"));

        AdminTariffDto dto = service.createTariff(70L, admin, req, http);

        assertEquals(900L, dto.getId());
        assertEquals("KZT", dto.getCurrency());
        ArgumentCaptor<AdminActionLog> logCap = ArgumentCaptor.forClass(AdminActionLog.class);
        verify(adminActionLogRepository).save(logCap.capture());
        assertEquals(AdminActionType.TARIFF_CREATED, logCap.getValue().getActionType());
    }

    @Test
    void listTariffs_throwsWhenServiceMissing() {
        when(serviceRepository.existsById(anyLong())).thenReturn(false);
        assertThrows(ResourceNotFoundException.class, () -> service.listTariffs(404L));
    }
}
