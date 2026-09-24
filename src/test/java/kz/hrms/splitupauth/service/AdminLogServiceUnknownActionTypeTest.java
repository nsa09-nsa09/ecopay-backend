package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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

/**
 * Regression guard: a row carrying an {@code action_type} that this build's {@link
 * kz.hrms.splitupauth.entity.AdminActionType} enum doesn't know about must NOT take down the whole
 * /admin/logs page.
 *
 * <p>Background: when {@code FEEDBACK_STATUS_CHANGED} was being added, the production DB briefly
 * held rows the old enum hadn't yet seen, and the entire admin logs endpoint started 500-ing
 * because Hibernate's {@code @Enumerated(EnumType.STRING)} path throws on unknown values. The fix
 * uses an {@code AttributeConverter} that maps unknowns to a sentinel rather than throwing.
 */
class AdminLogServiceUnknownActionTypeTest extends AbstractIntegrationTest {

  @Autowired AdminLogService adminLogService;
  @Autowired UserRepository userRepository;
  @Autowired JdbcTemplate jdbc;

  private static final AtomicInteger SEQ = new AtomicInteger();

  @Test
  void getAdminActionLogsPaged_doesNotThrowOnUnknownActionType() {
    String originalConstraintDefinition =
        jdbc.queryForObject(
            "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c "
                + "WHERE c.conname = 'chk_admin_action_log_action_type' "
                + "AND c.conrelid = 'admin_action_log'::regclass",
            String.class);
    assertNotNull(originalConstraintDefinition);
    User admin = saveAdmin();
    UUID eventId = UUID.randomUUID();
    try {
      jdbc.execute("ALTER TABLE admin_action_log DROP CONSTRAINT chk_admin_action_log_action_type");
      jdbc.update(
          "INSERT INTO admin_action_log "
              + "(event_id, admin_user_id, action_type, entity_type, entity_id, created_at) "
              + "VALUES (?, ?, 'SOME_FUTURE_ACTION', 'user', ?, NOW())",
          eventId,
          admin.getId(),
          admin.getId());
      // Keep the unknown row while enforcing the actual migrated rule for new writes.
      jdbc.execute(
          "ALTER TABLE admin_action_log ADD CONSTRAINT chk_admin_action_log_action_type "
              + originalConstraintDefinition
              + " NOT VALID");

      AdminActionLogFilterRequest filter = new AdminActionLogFilterRequest();
      PageResponse<AdminActionLogDto> page =
          assertDoesNotThrow(() -> adminLogService.getAdminActionLogsPaged(admin, filter, 0, 200));
      boolean sawUnknownSentinel =
          page.getItems().stream().anyMatch(dto -> "UNKNOWN".equals(dto.getActionType()));
      assertTrue(
          sawUnknownSentinel,
          "the stray SOME_FUTURE_ACTION row must surface as the UNKNOWN sentinel "
              + "in the DTO instead of crashing the hydration");
    } finally {
      try {
        // The audit table is append-only; lift only its DELETE guard for this cleanup.
        jdbc.execute("ALTER TABLE admin_action_log DISABLE TRIGGER trg_block_aal_delete");
        try {
          jdbc.update("DELETE FROM admin_action_log WHERE event_id = ?", eventId);
        } finally {
          jdbc.execute("ALTER TABLE admin_action_log ENABLE TRIGGER trg_block_aal_delete");
        }
      } finally {
        jdbc.execute(
            "ALTER TABLE admin_action_log DROP CONSTRAINT IF EXISTS chk_admin_action_log_action_type");
        jdbc.execute(
            "ALTER TABLE admin_action_log ADD CONSTRAINT chk_admin_action_log_action_type "
                + originalConstraintDefinition);
        userRepository.deleteById(admin.getId());
      }
    }

    assertEquals(
        originalConstraintDefinition,
        jdbc.queryForObject(
            "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c "
                + "WHERE c.conname = 'chk_admin_action_log_action_type' "
                + "AND c.conrelid = 'admin_action_log'::regclass",
            String.class));
    assertTrue(
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT c.convalidated FROM pg_constraint c "
                    + "WHERE c.conname = 'chk_admin_action_log_action_type' "
                    + "AND c.conrelid = 'admin_action_log'::regclass",
                Boolean.class)));
  }

  private User saveAdmin() {
    int n = SEQ.incrementAndGet();
    return userRepository.save(
        User.builder()
            .email("alog_admin_" + n + "_" + System.nanoTime() + "@t.kz")
            .password("x")
            .displayName("Admin " + n)
            .role(Role.ADMIN)
            .status(UserStatus.ACTIVE)
            .build());
  }
}
