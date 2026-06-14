package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.dto.AdminUserDto;
import kz.hrms.splitupauth.dto.DashboardMetricsDto;
import kz.hrms.splitupauth.dto.SiteContentDto;
import kz.hrms.splitupauth.dto.UpdateSiteContentRequest;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-Postgres integration coverage for the dashboard date-format fix and the
 * new admin-editable About Us content. Verifies:
 *
 * <ul>
 *   <li>{@code AdminDashboardService.getMetrics} accepts {@link LocalDate}
 *       endpoints (the controller binds {@code ?from=2025-01-01&to=2025-12-31}
 *       directly) and widens them to a full day-range without throwing
 *       MethodArgumentTypeMismatchException upstream.</li>
 *   <li>The V24 seed places a row at id=1, so a fresh deployment can read the
 *       About page without explicit initialization.</li>
 *   <li>{@code SiteContentService.updateAbout} mutates fields, sanitizes HTML,
 *       and appends a SITE_CONTENT_UPDATED audit log row — proving the V24
 *       CHECK constraint extension also took effect.</li>
 *   <li>{@code AdminUserDto.fromWithCounters} populates {@code status} for
 *       both BANNED and ACTIVE users, so the admin detail view can branch
 *       the Ban/Unban button (the source of the original UI confusion).</li>
 * </ul>
 */
class DashboardAndSiteContentIntegrationTest extends AbstractIntegrationTest {

    @Autowired AdminDashboardService adminDashboardService;
    @Autowired SiteContentService siteContentService;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final AtomicInteger SEQ = new AtomicInteger();
    private final MockHttpServletRequest http = new MockHttpServletRequest();

    private User admin() {
        int n = SEQ.incrementAndGet();
        User u = User.builder()
                .email("ddi_admin_" + n + "_" + System.nanoTime() + "@test.kz")
                .password("x")
                .displayName("Admin " + n)
                .role(Role.ADMIN)
                .status(UserStatus.ACTIVE)
                .build();
        return userRepository.save(u);
    }

    // ===================== Dashboard date format =====================

    @Test
    void dashboardMetrics_acceptsLocalDate_andCoversFullRange() {
        LocalDate from = LocalDate.now().minusMonths(3);
        LocalDate to = LocalDate.now();

        DashboardMetricsDto result = adminDashboardService.getMetrics("month", from, to);

        assertNotNull(result);
        assertEquals("month", result.getGranularity());
        // resolvedFrom is widened to 00:00:00 and resolvedTo to 23:59:59.999 inside
        // the service, so the response window covers the entire requested day range.
        assertEquals(from.atStartOfDay(), result.getFrom());
        assertTrue(result.getTo().toLocalDate().equals(to),
                "to should sit on the same calendar day");
        assertFalse(result.getSeries().isEmpty(),
                "monthly series across 3 months must produce at least one bucket");
    }

    @Test
    void dashboardMetrics_nullFromAndTo_defaultsToLast12Months() {
        DashboardMetricsDto result = adminDashboardService.getMetrics("month", null, null);

        assertNotNull(result);
        assertNotNull(result.getFrom());
        assertNotNull(result.getTo());
        assertFalse(result.getSeries().isEmpty());
    }

    @Test
    void dashboardMetrics_invalidGranularity_returns400() {
        assertThrows(InvalidRequestException.class,
                () -> adminDashboardService.getMetrics("week", LocalDate.now().minusDays(7), LocalDate.now()));
    }

    @Test
    void dashboardMetrics_fromAfterTo_returns400() {
        assertThrows(InvalidRequestException.class,
                () -> adminDashboardService.getMetrics("day",
                        LocalDate.now(), LocalDate.now().minusDays(1)));
    }

    // ===================== Site content =====================

    @Test
    void getAbout_returnsSeededDefault() {
        SiteContentDto dto = siteContentService.getAbout();
        assertNotNull(dto);
        assertEquals("EcoPay", dto.getCompanyName());
        assertEquals("О EcoPay", dto.getTitle());
        assertNotNull(dto.getMission());
    }

    @Test
    void updateAbout_writesFieldsAndAuditLog_andSanitizesHtml() {
        User adminUser = admin();
        long auditBefore = jdbcTemplate.queryForObject(
                "select count(*) from admin_action_log where action_type = 'SITE_CONTENT_UPDATED'",
                Long.class);

        UpdateSiteContentRequest req = new UpdateSiteContentRequest();
        req.setCompanyName("EcoPay KZ");
        req.setTitle("О EcoPay <script>alert(1)</script>");
        req.setMission("Mission text");
        req.setDescription("How we help");
        req.setContactEmail("hello@ecopay.kz");
        req.setContactPhone("+7 000 000 0000");

        SiteContentDto updated = siteContentService.updateAbout(adminUser, req, http);

        assertEquals("EcoPay KZ", updated.getCompanyName());
        assertEquals("О EcoPay alert(1)", updated.getTitle(),
                "HTML tags + lone brackets must be stripped server-side");
        assertEquals("Mission text", updated.getMission());

        long auditAfter = jdbcTemplate.queryForObject(
                "select count(*) from admin_action_log where action_type = 'SITE_CONTENT_UPDATED'",
                Long.class);
        assertEquals(auditBefore + 1, auditAfter,
                "Each successful update must append a SITE_CONTENT_UPDATED audit row "
                        + "(also proves V24 CHECK constraint allows the new value)");

        // Subsequent reads return the new values — singleton stays at id=1.
        SiteContentDto fresh = siteContentService.getAbout();
        assertEquals("EcoPay KZ", fresh.getCompanyName());
    }

    // ===================== Unban detail DTO sanity =====================
    //
    // The unban controller code path is unchanged; this guards the original
    // source of frontend confusion ("кнопка не помечается, потому что status
    // не заполнен в детали"). AdminUserDto.fromWithCounters must surface the
    // status string for both BANNED and ACTIVE users so the React UI can
    // branch on it.

    @Test
    void adminUserDetailDto_includesStatus_forBannedAndActive() {
        User banned = userRepository.save(User.builder()
                .email("ban_dto_" + System.nanoTime() + "@test.kz")
                .password("x")
                .displayName("Banned")
                .role(Role.USER)
                .status(UserStatus.BANNED)
                .build());

        AdminUserDto bannedDto = AdminUserDto.fromWithCounters(banned, 0L, 0L, 0L, 0L);
        assertEquals("BANNED", bannedDto.getStatus());

        banned.setStatus(UserStatus.ACTIVE);
        banned.setBanReason(null);
        banned.setBannedAt(null);
        userRepository.save(banned);

        AdminUserDto activeDto = AdminUserDto.fromWithCounters(banned, 0L, 0L, 0L, 0L);
        assertEquals("ACTIVE", activeDto.getStatus());
        assertNull(banned.getBanReason());
        assertNull(banned.getBannedAt());
    }
}
