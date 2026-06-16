package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.dto.AdminActionLogDto;
import kz.hrms.splitupauth.dto.AdminActionLogFilterRequest;
import kz.hrms.splitupauth.dto.PageResponse;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard: a row carrying an {@code action_type} that this build's
 * {@link kz.hrms.splitupauth.entity.AdminActionType} enum doesn't know about
 * must NOT take down the whole /admin/logs page.
 *
 * <p>Background: when {@code FEEDBACK_STATUS_CHANGED} was being added, the
 * production DB briefly held rows the old enum hadn't yet seen, and the entire
 * admin logs endpoint started 500-ing because Hibernate's
 * {@code @Enumerated(EnumType.STRING)} path throws on unknown values. The fix
 * uses an {@code AttributeConverter} that maps unknowns to a sentinel rather
 * than throwing.
 */
class AdminLogServiceUnknownActionTypeTest extends AbstractIntegrationTest {

    @Autowired AdminLogService adminLogService;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Test
    void getAdminActionLogsPaged_doesNotThrowOnUnknownActionType() {
        User admin = saveAdmin();

        // CHECK constraint chk_admin_action_log_action_type would normally
        // reject anything outside the known enum set. Temporarily relax it so
        // we can simulate the production scenario (a row whose action_type the
        // current build doesn't know about). The constraint is re-added with
        // NOT VALID so this single stray row stays in place but future writes
        // remain strict — sibling tests keep their guarantees.
        jdbc.execute(
                "ALTER TABLE admin_action_log DROP CONSTRAINT IF EXISTS chk_admin_action_log_action_type");

        UUID eventId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO admin_action_log "
                        + "(event_id, admin_user_id, action_type, entity_type, entity_id, created_at) "
                        + "VALUES (?, ?, 'SOME_FUTURE_ACTION', 'user', ?, NOW())",
                eventId, admin.getId(), admin.getId());

        // Re-add the same constraint but only for *new* writes — the stray row
        // skips validation thanks to NOT VALID. Mirrors what a real schema
        // would look like mid-migration.
        jdbc.execute(
                "ALTER TABLE admin_action_log "
                        + "ADD CONSTRAINT chk_admin_action_log_action_type "
                        + "CHECK (action_type IN ("
                        + "'ACCESS_CONFIRMED','ACCESS_REJECTED','ROOM_BLOCKED',"
                        + "'USER_BANNED','USER_UNBANNED','USER_CREATED','USER_ROLE_CHANGED',"
                        + "'REFUND_INITIATED','REFUND_APPROVED','REFUND_REJECTED',"
                        + "'DISPUTE_RESOLVED','BATCH_CONFIRM','OWNER_VERIFICATION_CHANGED',"
                        + "'CATEGORY_CREATED','CATEGORY_UPDATED','CATEGORY_DELETED',"
                        + "'SERVICE_CREATED','SERVICE_UPDATED','SERVICE_DELETED',"
                        + "'TARIFF_CREATED','TARIFF_UPDATED','TARIFF_DELETED',"
                        + "'TESTIMONIAL_FEATURED','TESTIMONIAL_UNFEATURED','TESTIMONIAL_EDITED',"
                        + "'TESTIMONIAL_DELETED','SITE_CONTENT_UPDATED',"
                        + "'FEEDBACK_STATUS_CHANGED','FEEDBACK_NOTE_UPDATED'"
                        + ")) NOT VALID");

        // The actual assertion: hydrating that row must not blow up the
        // endpoint. Before the converter fix Hibernate threw and the entire
        // admin-logs page 500-ed.
        AdminActionLogFilterRequest filter = new AdminActionLogFilterRequest();
        PageResponse<AdminActionLogDto> page =
                assertDoesNotThrow(() ->
                        adminLogService.getAdminActionLogsPaged(admin, filter, 0, 200));

        boolean sawUnknownSentinel = page.getItems().stream()
                .anyMatch(dto -> "UNKNOWN".equals(dto.getActionType()));
        assertTrue(sawUnknownSentinel,
                "the stray SOME_FUTURE_ACTION row must surface as the UNKNOWN sentinel "
                        + "in the DTO instead of crashing the hydration");
    }

    private User saveAdmin() {
        int n = SEQ.incrementAndGet();
        return userRepository.save(User.builder()
                .email("alog_admin_" + n + "_" + System.nanoTime() + "@t.kz")
                .password("x")
                .displayName("Admin " + n)
                .role(Role.ADMIN)
                .status(UserStatus.ACTIVE)
                .build());
    }
}
