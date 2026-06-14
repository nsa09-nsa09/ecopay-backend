package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.ServiceDto;
import kz.hrms.splitupauth.entity.Category;
import kz.hrms.splitupauth.entity.PeriodType;
import kz.hrms.splitupauth.entity.ProviderType;
import kz.hrms.splitupauth.entity.ServiceEntity;
import kz.hrms.splitupauth.entity.TariffPlan;
import kz.hrms.splitupauth.repository.CategoryRepository;
import kz.hrms.splitupauth.repository.ServiceRepository;
import kz.hrms.splitupauth.repository.TariffPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    @Mock private CategoryRepository categoryRepository;
    @Mock private ServiceRepository serviceRepository;
    @Mock private TariffPlanRepository tariffPlanRepository;

    private CatalogService service;
    private Category category;

    @BeforeEach
    void setUp() {
        service = new CatalogService(categoryRepository, serviceRepository,
                tariffPlanRepository, new CatalogMapper());
        category = Category.builder().id(1L).name("Video").slug("video").isActive(true).sortOrder(0).build();
    }

    private ServiceEntity svc(long id, String name) {
        return ServiceEntity.builder()
                .id(id).category(category).name(name).slug("s-" + id)
                .providerType(ProviderType.DIGITAL).isActive(true).build();
    }

    private TariffPlan tariff(long id, ServiceEntity s, BigDecimal price, int seats, String currency) {
        return TariffPlan.builder()
                .id(id).service(s).name("t" + id).periodType(PeriodType.MONTHLY)
                .maxMembers(seats).basePriceTotal(price).currency(currency).isActive(true).build();
    }

    @Test
    void getServices_priceAsc_sortsByMinPricePerMember_andPushesNullsToEnd() {
        ServiceEntity netflix = svc(1L, "Netflix");
        ServiceEntity yt = svc(2L, "YouTube");
        ServiceEntity noTariffs = svc(3L, "No Tariff Service");
        when(serviceRepository.findByIsActiveTrueOrderByIdAsc())
                .thenReturn(List.of(netflix, yt, noTariffs));
        // Netflix: 7290/4 = 1822.50; YouTube: 3160/4 = 790.00 → YT cheapest.
        when(tariffPlanRepository.findByServiceIdInAndIsActiveTrue(anyList()))
                .thenReturn(List.of(
                        tariff(10L, netflix, new BigDecimal("7290.00"), 4, "KZT"),
                        tariff(11L, yt, new BigDecimal("3160.00"), 4, "KZT")
                ));

        List<ServiceDto> result = service.getServices(null, "price_asc");

        assertEquals(3, result.size());
        assertEquals("YouTube", result.get(0).getName());
        assertEquals(0, new BigDecimal("790.00").compareTo(result.get(0).getMinPricePerMember()));
        assertEquals("Netflix", result.get(1).getName());
        assertEquals("No Tariff Service", result.get(2).getName(), "services with no tariffs must come last");
        assertNull(result.get(2).getMinPricePerMember());
    }

    @Test
    void getServices_priceDesc_pushesNullsToEnd() {
        ServiceEntity a = svc(1L, "Aaa");
        ServiceEntity b = svc(2L, "Bbb");
        ServiceEntity none = svc(3L, "Cnone");
        when(serviceRepository.findByIsActiveTrueOrderByIdAsc())
                .thenReturn(List.of(a, b, none));
        when(tariffPlanRepository.findByServiceIdInAndIsActiveTrue(anyList()))
                .thenReturn(List.of(
                        tariff(20L, a, new BigDecimal("100.00"), 2, "KZT"),
                        tariff(21L, b, new BigDecimal("400.00"), 2, "KZT")
                ));

        List<ServiceDto> result = service.getServices(null, "price_desc");

        assertEquals("Bbb", result.get(0).getName());
        assertEquals("Aaa", result.get(1).getName());
        assertEquals("Cnone", result.get(2).getName());
    }

    @Test
    void getServices_nameDesc_works() {
        ServiceEntity a = svc(1L, "Apple");
        ServiceEntity b = svc(2L, "Banana");
        when(serviceRepository.findByIsActiveTrueOrderByIdAsc()).thenReturn(List.of(a, b));
        when(tariffPlanRepository.findByServiceIdInAndIsActiveTrue(anyList())).thenReturn(List.of());

        List<ServiceDto> result = service.getServices(null, "name_desc");

        assertEquals("Banana", result.get(0).getName());
        assertEquals("Apple", result.get(1).getName());
    }

    @Test
    void getServices_newest_putsHighestIdFirst() {
        ServiceEntity a = svc(1L, "Old");
        ServiceEntity b = svc(99L, "New");
        when(serviceRepository.findByIsActiveTrueOrderByIdAsc()).thenReturn(List.of(a, b));
        when(tariffPlanRepository.findByServiceIdInAndIsActiveTrue(anyList())).thenReturn(List.of());

        List<ServiceDto> result = service.getServices(null, "newest");

        assertEquals(99L, result.get(0).getId());
        assertEquals(1L, result.get(1).getId());
    }

    @Test
    void getServices_setsCurrencyAndTariffCount_correctly() {
        ServiceEntity netflix = svc(1L, "Netflix");
        when(serviceRepository.findByIsActiveTrueOrderByIdAsc()).thenReturn(List.of(netflix));
        when(tariffPlanRepository.findByServiceIdInAndIsActiveTrue(anyList()))
                .thenReturn(List.of(
                        tariff(10L, netflix, new BigDecimal("7290.00"), 4, "KZT"),
                        tariff(11L, netflix, new BigDecimal("11990.00"), 4, "KZT")
                ));

        List<ServiceDto> result = service.getServices(null, "name_asc");

        assertEquals(1, result.size());
        assertEquals(2, result.get(0).getTariffCount());
        assertEquals("KZT", result.get(0).getCurrency());
        // 7290/4 = 1822.50 is cheapest.
        assertEquals(0, new BigDecimal("1822.50").compareTo(result.get(0).getMinPricePerMember()));
    }
}
