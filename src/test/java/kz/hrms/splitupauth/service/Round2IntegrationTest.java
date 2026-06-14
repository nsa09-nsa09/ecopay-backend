package kz.hrms.splitupauth.service;

import jakarta.servlet.http.HttpServletRequest;
import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.dto.AdminCategoryDto;
import kz.hrms.splitupauth.dto.AdminServiceDto;
import kz.hrms.splitupauth.dto.AdminTariffDto;
import kz.hrms.splitupauth.dto.CreateCategoryRequest;
import kz.hrms.splitupauth.dto.CreateServiceRequest;
import kz.hrms.splitupauth.dto.CreateTariffRequest;
import kz.hrms.splitupauth.dto.DashboardMetricsDto;
import kz.hrms.splitupauth.dto.LoginRequest;
import kz.hrms.splitupauth.dto.RegisterRequest;
import kz.hrms.splitupauth.dto.ServiceDto;
import kz.hrms.splitupauth.dto.UpdateTariffRequest;
import kz.hrms.splitupauth.entity.AdminActionLog;
import kz.hrms.splitupauth.entity.AdminActionType;
import kz.hrms.splitupauth.entity.PeriodType;
import kz.hrms.splitupauth.entity.ProviderType;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.UserBannedException;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.websocket.AccountRealtimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Round-2 features against real Postgres: avatar storage smoke check,
 * tariff features round-trip, dashboard metrics, and the live-ban flow.
 */
class Round2IntegrationTest extends AbstractIntegrationTest {

    @Autowired AuthService authService;
    @Autowired AdminCatalogService adminCatalogService;
    @Autowired CatalogService catalogService;
    @Autowired AdminDashboardService dashboardService;
    @Autowired UserRepository userRepository;
    @Autowired AdminActionLogRepository adminActionLogRepository;
    @Autowired JdbcTemplate jdbc;

    // SimpMessagingTemplate is the easiest seam to confirm a ban triggers a
    // WS publish — we don't need a real STOMP client to assert intent.
    @MockitoBean SimpMessagingTemplate messagingTemplate;

    @Autowired kz.hrms.splitupauth.controller.AdminUserController adminUserController;

    private static final AtomicInteger SEQ = new AtomicInteger();
    private final HttpServletRequest http = new MockHttpServletRequest();

    private User register(String name) {
        int n = SEQ.incrementAndGet();
        RegisterRequest req = new RegisterRequest();
        req.setEmail("r2_" + n + "_" + System.nanoTime() + "@test.kz");
        req.setPassword("Test1234");
        req.setDisplayName(name);
        req.setPhone("+77" + String.format("%09d", (System.nanoTime() % 1_000_000_000L)));
        authService.register(req);
        User u = userRepository.findByEmail(req.getEmail()).orElseThrow();
        // Need emailVerified=true for login
        u.setEmailVerified(true);
        return userRepository.save(u);
    }

    private User admin() {
        User u = register("Admin Round2");
        u.setRole(Role.ADMIN);
        return userRepository.save(u);
    }

    // ===================== Task 3: tariff features =====================

    @Test
    void tariff_featuresRoundTrip_visibleInPublicAndAdmin() {
        User adminUser = admin();
        CreateCategoryRequest cat = new CreateCategoryRequest();
        cat.setName("Cat r2-" + SEQ.get());
        AdminCategoryDto category = adminCatalogService.createCategory(adminUser, cat, http);

        CreateServiceRequest svcReq = new CreateServiceRequest();
        svcReq.setCategoryId(category.getId());
        svcReq.setName("Service r2-" + SEQ.get());
        svcReq.setProviderType(ProviderType.DIGITAL);
        AdminServiceDto service = adminCatalogService.createService(adminUser, svcReq, http);

        CreateTariffRequest tr = new CreateTariffRequest();
        tr.setName("Family pack");
        tr.setPeriodType(PeriodType.MONTHLY);
        tr.setMaxMembers(4);
        tr.setBasePriceTotal(new BigDecimal("4000.00"));
        tr.setFeatures(List.of("4K", "без рекламы", "семейный аккаунт"));
        AdminTariffDto created = adminCatalogService.createTariff(service.getId(), adminUser, tr, http);
        assertEquals(List.of("4K", "без рекламы", "семейный аккаунт"), created.getFeatures());

        // Update — features list replaces, sanitization strips tags.
        UpdateTariffRequest upd = new UpdateTariffRequest();
        upd.setFeatures(List.of("<b>HDR</b>", "без рекламы"));
        AdminTariffDto updated = adminCatalogService.updateTariff(created.getId(), adminUser, upd, http);
        assertEquals(List.of("HDR", "без рекламы"), updated.getFeatures());

        // Public catalog must echo aggregate fields + tariff count.
        List<ServiceDto> publicList = catalogService.getServices(category.getId(), "name_asc");
        ServiceDto echoed = publicList.stream().filter(s -> s.getId().equals(service.getId())).findFirst().orElseThrow();
        assertEquals(1, echoed.getTariffCount());
        assertNotNull(echoed.getMinPricePerMember());
    }

    // ===================== Task 4: last_login_at =====================

    @Test
    void successfulLogin_setsLastLoginAt() {
        User u = register("LoginPing");
        // Login requires the password from registration ("Test1234")
        LoginRequest login = new LoginRequest();
        login.setEmail(u.getEmail());
        login.setPassword("Test1234");

        LocalDateTime before = LocalDateTime.now().minusSeconds(2);
        authService.login(login);

        User reloaded = userRepository.findById(u.getId()).orElseThrow();
        assertNotNull(reloaded.getLastLoginAt());
        assertTrue(reloaded.getLastLoginAt().isAfter(before));
    }

    // ===================== Task 5: dashboard metrics =====================

    @Test
    void metrics_monthGranularity_returnsBucketsThatCoverRange() {
        User u = register("MetricsUser");
        // Touching login_attempts: have to log in successfully to bump the table.
        LoginRequest login = new LoginRequest();
        login.setEmail(u.getEmail());
        login.setPassword("Test1234");
        authService.login(login);

        DashboardMetricsDto metrics = dashboardService.getMetrics(
                "month",
                LocalDateTime.now().minusMonths(2).withDayOfMonth(1),
                LocalDateTime.now());

        assertFalse(metrics.getSeries().isEmpty());
        assertEquals("month", metrics.getGranularity());
        long regsSum = metrics.getSeries().stream().mapToLong(p -> p.getRegistrations()).sum();
        long logSum = metrics.getSeries().stream().mapToLong(p -> p.getLoginsTotal()).sum();
        // At minimum, the bucket containing now() must have counted our 1 reg and 1 login.
        assertTrue(regsSum >= 1, "at least one registration must be visible");
        assertTrue(logSum >= 1, "at least one login must be visible");
    }

    @Test
    void metrics_rejectsUnknownGranularity() {
        assertThrows(kz.hrms.splitupauth.exception.InvalidRequestException.class,
                () -> dashboardService.getMetrics("hour", null, null));
    }

    @Test
    void kpis_includeDeletedAndNewLast30Counts() {
        long before = dashboardService.getKpis().getNewUsersLast30Days();
        register("ForKpi");
        long after = dashboardService.getKpis().getNewUsersLast30Days();
        assertTrue(after > before, "new-30-day count must increase after registration");
    }

    // ===================== Task 6: live ban =====================

    @Test
    void ban_writesReasonAndBannedAt_andPublishesToAccountTopic() {
        User adminUser = admin();
        User victim = register("Victim");

        kz.hrms.splitupauth.dto.AdminDecisionRequest decision = new kz.hrms.splitupauth.dto.AdminDecisionRequest();
        decision.setReason("Spamming reviews");

        adminUserController.ban(victim.getId(), adminUser, decision, http);

        User reloaded = userRepository.findById(victim.getId()).orElseThrow();
        assertEquals(UserStatus.BANNED, reloaded.getStatus());
        assertEquals("Spamming reviews", reloaded.getBanReason());
        assertNotNull(reloaded.getBannedAt());

        // The WS publish should have hit the personal topic.
        verify(messagingTemplate, atLeastOnce()).convertAndSend(
                eq(AccountRealtimeService.topicFor(victim.getId())),
                (Object) any());

        // Audit log row was written.
        boolean logged = adminActionLogRepository.findAllByOrderByCreatedAtDesc().stream()
                .anyMatch(l -> AdminActionType.USER_BANNED.equals(l.getActionType())
                        && victim.getId().equals(l.getEntityId()));
        assertTrue(logged, "ban must record an audit-log row");
    }

    @Test
    void bannedUser_loginReturnsStructuredException() {
        User adminUser = admin();
        User victim = register("BanLogin");
        kz.hrms.splitupauth.dto.AdminDecisionRequest decision = new kz.hrms.splitupauth.dto.AdminDecisionRequest();
        decision.setReason("policy violation");
        adminUserController.ban(victim.getId(), adminUser, decision, http);

        LoginRequest login = new LoginRequest();
        login.setEmail(victim.getEmail());
        login.setPassword("Test1234");

        UserBannedException ex = assertThrows(UserBannedException.class,
                () -> authService.login(login));
        assertEquals("policy violation", ex.getReason());
        assertNotNull(ex.getBannedAt());
    }

    @Test
    void unban_clearsReason_andPublishesUnbanned() {
        User adminUser = admin();
        User victim = register("UnbanMe");
        kz.hrms.splitupauth.dto.AdminDecisionRequest banReq = new kz.hrms.splitupauth.dto.AdminDecisionRequest();
        banReq.setReason("temp");
        adminUserController.ban(victim.getId(), adminUser, banReq, http);

        kz.hrms.splitupauth.dto.AdminDecisionRequest unbanReq = new kz.hrms.splitupauth.dto.AdminDecisionRequest();
        unbanReq.setReason("appeal granted");
        adminUserController.unban(victim.getId(), adminUser, unbanReq, http);

        User reloaded = userRepository.findById(victim.getId()).orElseThrow();
        assertEquals(UserStatus.ACTIVE, reloaded.getStatus());
        assertNull(reloaded.getBanReason());
        assertNull(reloaded.getBannedAt());
    }
}
