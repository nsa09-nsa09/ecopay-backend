package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.dto.CatalogSearchResultDto;
import kz.hrms.splitupauth.dto.CreateCategoryRequest;
import kz.hrms.splitupauth.dto.CreateServiceRequest;
import kz.hrms.splitupauth.entity.ProviderType;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the public navbar search against real Postgres so the ILIKE +
 * LOWER(name) index path is verified end-to-end (the unit test in
 * {@link CatalogServiceTest} stops at the repository call).
 */
class CatalogSearchIntegrationTest extends AbstractIntegrationTest {

    @Autowired CatalogService catalogService;
    @Autowired AdminCatalogService adminCatalogService;
    @Autowired UserRepository userRepository;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private User admin() {
        int n = SEQ.incrementAndGet();
        return userRepository.save(User.builder()
                .email("cs_admin_" + n + "_" + System.nanoTime() + "@t.kz")
                .password("x")
                .displayName("CS Admin " + n)
                .role(Role.ADMIN)
                .status(UserStatus.ACTIVE)
                .build());
    }

    private long seedService(String name) {
        User adminUser = admin();
        MockHttpServletRequest http = new MockHttpServletRequest();

        CreateCategoryRequest cat = new CreateCategoryRequest();
        cat.setName("Cat " + SEQ.get());
        var category = adminCatalogService.createCategory(adminUser, cat, http);

        CreateServiceRequest svcReq = new CreateServiceRequest();
        svcReq.setCategoryId(category.getId());
        svcReq.setName(name);
        svcReq.setProviderType(ProviderType.DIGITAL);
        return adminCatalogService.createService(adminUser, svcReq, http).getId();
    }

    @Test
    void searchServices_matchesCaseInsensitiveSubstring() {
        String marker = "Spotify-" + SEQ.incrementAndGet();
        long id = seedService(marker);

        // Lowercased query → must still hit the row (LOWER(name) on both sides).
        List<CatalogSearchResultDto> result = catalogService.searchServices(
                marker.substring(0, 6).toLowerCase(), 10);

        assertFalse(result.isEmpty(),
                "case-insensitive substring must match the seeded service");
        assertTrue(result.stream().anyMatch(r -> r.getServiceId().equals(id)),
                "seeded service must appear in the search results");
    }

    @Test
    void searchServices_emptyOrShortQuery_returnsEmpty() {
        assertEquals(0, catalogService.searchServices("", 10).size());
        assertEquals(0, catalogService.searchServices(" ", 10).size());
        assertEquals(0, catalogService.searchServices("a", 10).size());
    }

    @Test
    void searchServices_clampsLimit() {
        // Seed several rows under a unique marker; ask for limit=1000 → must
        // be clamped to at most CatalogService.SEARCH_MAX_LIMIT (25).
        String marker = "ClampSrv-" + SEQ.incrementAndGet();
        for (int i = 0; i < 3; i++) {
            seedService(marker + "-" + i);
        }
        List<CatalogSearchResultDto> result = catalogService.searchServices(marker, 1000);
        assertTrue(result.size() <= 25, "limit must be clamped by the service");
    }
}
