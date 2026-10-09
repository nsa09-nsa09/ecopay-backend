package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import kz.hrms.splitupauth.entity.AdminActionLog;
import kz.hrms.splitupauth.entity.AdminActionType;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit-log writer for the news module that's deliberately decoupled from the caller's transaction.
 *
 * <p><b>Why a separate component?</b> {@link Propagation#REQUIRES_NEW} only takes effect when
 * invoked through Spring's proxy — self-calls inside {@code NewsService} would still run in the
 * outer transaction. Splitting the writer out gives us a real proxy boundary so:
 *
 * <ul>
 *   <li>A failing {@code admin_action_log} insert (CHECK constraint mismatch, missing column on the
 *       live DB after a forgotten migration, …) only rolls back the audit row — the user-visible
 *       operation ({@code POST /api/v1/admin/news}, etc.) still commits and returns 201.
 *   <li>The exception is swallowed to a {@code WARN}, so the client never sees a 5xx because the
 *       audit pipeline is wedged.
 * </ul>
 *
 * <p>The trade-off is: in the rare case audit-write fails, the news write still lands without a
 * corresponding log row. That's the right call here — losing a single audit entry beats losing
 * user-visible reliability, and the WARN log is enough for an operator to notice the misconfig and
 * re-baseline.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NewsAuditWriter {

  private final AdminActionLogRepository adminActionLogRepository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void writeOrSwallow(
      User admin,
      AdminActionType type,
      Long entityId,
      ObjectNode oldState,
      ObjectNode newState,
      HttpServletRequest http) {
    try {
      adminActionLogRepository.save(
          AdminActionLog.builder()
              .eventId(UUID.randomUUID())
              .adminUser(admin)
              .actionType(type)
              .entityType("NEWS")
              .entityId(entityId)
              .reason(null)
              .oldState(oldState)
              .newState(newState)
              .ipAddress(http != null ? http.getRemoteAddr() : null)
              .userAgent(http != null ? http.getHeader("User-Agent") : null)
              .build());
    } catch (RuntimeException ex) {
      // Most common cause: the chk_admin_action_log_action_type CHECK
      // constraint on the live DB hasn't been migrated to know NEWS_*
      // yet. We deliberately don't rethrow — see the class javadoc.
      log.warn("admin_action_log write failed for {} {}: {}", type, entityId, ex.getMessage());
    }
  }
}
