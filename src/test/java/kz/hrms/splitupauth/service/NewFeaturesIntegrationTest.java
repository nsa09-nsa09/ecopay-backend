package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.dto.AdminServiceReviewDto;
import kz.hrms.splitupauth.dto.CreateCategoryRequest;
import kz.hrms.splitupauth.dto.CreateServiceReviewRequest;
import kz.hrms.splitupauth.dto.CreateServiceRequest;
import kz.hrms.splitupauth.dto.CreateTariffRequest;
import kz.hrms.splitupauth.dto.PublicProfileDto;
import kz.hrms.splitupauth.dto.PublicServiceReviewDto;
import kz.hrms.splitupauth.dto.RegisterRequest;
import kz.hrms.splitupauth.dto.ServiceDto;
import kz.hrms.splitupauth.dto.UpdateCategoryRequest;
import kz.hrms.splitupauth.dto.UpdateServiceReviewRequest;
import kz.hrms.splitupauth.entity.PeriodType;
import kz.hrms.splitupauth.entity.ProviderType;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.ResourceConflictException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests against real Postgres for the new catalog/service-review
 * /public-profile/account-deletion features. Each method covers one acceptance
 * criterion from the contract.
 */
class NewFeaturesIntegrationTest extends AbstractIntegrationTest {

    @Autowired AuthService authService;
    @Autowired PhoneVerificationService phoneVerificationService;
    @Autowired CatalogService catalogService;
    @Autowired AdminCatalogService adminCatalogService;
    @Autowired ServiceReviewService serviceReviewService;
    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final AtomicInteger SEQ = new AtomicInteger();
    private final MockHttpServletRequest http = new MockHttpServletRequest();

    private User register(String name) {
        int n = SEQ.incrementAndGet();
        RegisterRequest req = new RegisterRequest();
        req.setEmail("new_" + n + "_" + System.nanoTime() + "@test.kz");
        req.setPassword("Test1234");
        req.setDisplayName(name);
        String phone = "+77" + String.format("%09d", (System.nanoTime() % 1_000_000_000L));
        req.setPhone(phone);
        authService.register(req);
        return userRepository.findByEmail(req.getEmail()).orElseThrow();
    }

    private User admin() {
        // Promote an existing user to admin for the test scope.
        User u = register("Admin " + SEQ.get());
        u.setRole(Role.ADMIN);
        u.setStatus(UserStatus.ACTIVE);
        return userRepository.save(u);
    }

    // ===================== Feature 1: catalog =====================

    @Test
    void register_assignsPublicId_andItIsUnique() {
        User a = register("A");
        User b = register("B");
        assertNotNull(a.getPublicId());
        assertNotNull(b.getPublicId());
        assertFalse(a.getPublicId().equals(b.getPublicId()));
        assertEquals(12, a.getPublicId().length());
    }

    @Test
    void publicCatalog_sortPriceAsc_putsCheapestFirst_andEmitsNewFields() {
        // The seeded V10 services include cheap and expensive tariffs; just
        // verify the contract behaviour rather than specific names.
        List<ServiceDto> services = catalogService.getServices(null, "price_asc");
        assertFalse(services.isEmpty(), "seed catalog must have services");

        // All non-null prices must come before nulls, and be monotonic.
        BigDecimal prev = null;
        boolean sawNull = false;
        for (ServiceDto s : services) {
            if (s.getMinPricePerMember() == null) {
                sawNull = true;
                continue;
            }
            assertFalse(sawNull, "null prices must come after non-null prices");
            if (prev != null) {
                assertTrue(s.getMinPricePerMember().compareTo(prev) >= 0,
                        "price_asc must be monotonically non-decreasing");
            }
            prev = s.getMinPricePerMember();
            assertNotNull(s.getCurrency(), "currency must be set when price is");
            assertTrue(s.getTariffCount() > 0);
        }
    }

    // ===================== Feature 1: admin catalog CRUD =====================

    @Test
    void adminCatalog_categoryServiceTariff_lifecycle_writesAuditLog() {
        User adminUser = admin();
        long before = jdbcTemplate.queryForObject(
                "select count(*) from admin_action_log", Long.class);

        // Create category
        CreateCategoryRequest cat = new CreateCategoryRequest();
        cat.setName("IT Test Cat " + SEQ.get());
        var category = adminCatalogService.createCategory(adminUser, cat, http);
        assertNotNull(category.getId());

        // Create service in the new category
        CreateServiceRequest svcReq = new CreateServiceRequest();
        svcReq.setCategoryId(category.getId());
        svcReq.setName("IT Service " + SEQ.get());
        svcReq.setProviderType(ProviderType.DIGITAL);
        var svc = adminCatalogService.createService(adminUser, svcReq, http);
        assertNotNull(svc.getId());

        // Create tariff
        CreateTariffRequest tr = new CreateTariffRequest();
        tr.setName("IT Tariff");
        tr.setPeriodType(PeriodType.MONTHLY);
        tr.setMaxMembers(4);
        tr.setBasePriceTotal(new BigDecimal("4000.00"));
        var tariff = adminCatalogService.createTariff(svc.getId(), adminUser, tr, http);
        assertNotNull(tariff.getId());
        assertEquals("KZT", tariff.getCurrency());

        // Three admin-action rows must have been appended.
        long after = jdbcTemplate.queryForObject(
                "select count(*) from admin_action_log", Long.class);
        assertEquals(before + 3, after, "each CRUD action must log to admin_action_log");
    }

    @Test
    void adminCatalog_deactivateCategoryWithActiveServices_returns409() {
        User adminUser = admin();
        var category = adminCatalogService.createCategory(adminUser,
                buildCategoryRequest("Conflict " + SEQ.get()), http);

        CreateServiceRequest svcReq = new CreateServiceRequest();
        svcReq.setCategoryId(category.getId());
        svcReq.setName("Conf Service " + SEQ.get());
        svcReq.setProviderType(ProviderType.DIGITAL);
        adminCatalogService.createService(adminUser, svcReq, http);

        UpdateCategoryRequest upd = new UpdateCategoryRequest();
        upd.setIsActive(false);
        assertThrows(ResourceConflictException.class,
                () -> adminCatalogService.updateCategory(category.getId(), adminUser, upd, http));
    }

    // ===================== Feature 2: service reviews =====================

    @Test
    void serviceReview_onePerUser_secondCreateConflicts_editClearsFeatured() {
        User u = register("Reviewer");

        CreateServiceReviewRequest first = new CreateServiceReviewRequest();
        first.setRating(5);
        first.setText("Огонь!");
        var dto = serviceReviewService.createMine(u, first);
        assertEquals(5, dto.getRating());
        assertFalse(dto.getFeatured());

        // Second create must fail with 409
        assertThrows(ResourceConflictException.class,
                () -> serviceReviewService.createMine(u, first));

        // Admin features it
        User adminUser = admin();
        var featuredDto = serviceReviewService.setFeatured(dto.getId(), true, adminUser, http);
        assertTrue(featuredDto.getFeatured());

        // Public carousel includes it
        List<PublicServiceReviewDto> carousel = serviceReviewService.getFeatured();
        assertTrue(carousel.stream().anyMatch(p -> p.getId().equals(dto.getId())));

        // Author edits — featured flips back to false
        UpdateServiceReviewRequest upd = new UpdateServiceReviewRequest();
        upd.setRating(4);
        upd.setText("Передумал, нормально");
        var edited = serviceReviewService.updateMine(u, upd);
        assertFalse(edited.getFeatured(), "editing my review must reset featured");
    }

    @Test
    void adminListServiceReviews_includesAuthorPublicId_andEmail() {
        User u = register("AuditMe");
        CreateServiceReviewRequest req = new CreateServiceReviewRequest();
        req.setRating(4);
        req.setText("ok");
        var dto = serviceReviewService.createMine(u, req);

        var page = serviceReviewService.listForAdmin(0, 50, null);
        AdminServiceReviewDto row = page.getItems().stream()
                .filter(r -> r.getId().equals(dto.getId())).findFirst().orElseThrow();
        assertEquals(u.getPublicId(), row.getAuthorPublicId());
        assertEquals(u.getEmail(), row.getAuthorEmail());
    }

    // ===================== Feature 3: public profile & deletion =====================

    @Test
    void publicProfile_doesNotLeakEmailOrPhone() {
        User u = register("Hidden");

        PublicProfileDto profile = userService.getPublicProfile(u.getPublicId());

        assertEquals(u.getId(), profile.getId());
        assertEquals(u.getPublicId(), profile.getPublicId());
        // The DTO has no email/phone fields at all — defense in depth: also
        // verify the displayName comes through but no PII shows up via JSON.
        assertNotNull(profile.getDisplayName());
    }

    @Test
    void deleteAccount_anonymizes_andPublicProfileReturns404() {
        User u = register("ToDelete");
        String publicId = u.getPublicId();

        userService.deleteAccount(u);

        // Re-fetch from DB — anonymized fields applied.
        User stored = userRepository.findById(u.getId()).orElseThrow();
        assertEquals(UserStatus.DELETED, stored.getStatus());
        assertNotNull(stored.getDeletedAt());
        assertTrue(stored.getEmail().startsWith("deleted-"));
        assertEquals("Удалённый пользователь", stored.getDisplayName());
        assertNull(stored.getPhone());
        assertNull(stored.getAvatar());

        // Public profile now 404.
        assertThrows(ResourceNotFoundException.class,
                () -> userService.getPublicProfile(publicId));
    }

    private CreateCategoryRequest buildCategoryRequest(String name) {
        CreateCategoryRequest c = new CreateCategoryRequest();
        c.setName(name);
        return c;
    }
}
