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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
public class StoryAuditWriter {

  private final AdminActionLogRepository adminActionLogRepository;
  private final PlatformTransactionManager transactionManager;

  public void writeOrSwallow(
      User admin,
      AdminActionType type,
      Long entityId,
      ObjectNode oldState,
      ObjectNode newState,
      HttpServletRequest http) {
    try {
      TransactionTemplate tx = new TransactionTemplate(transactionManager);
      tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      tx.executeWithoutResult(
          status ->
              adminActionLogRepository.saveAndFlush(
                  AdminActionLog.builder()
                      .eventId(UUID.randomUUID())
                      .adminUser(admin)
                      .actionType(type)
                      .entityType("STORY")
                      .entityId(entityId)
                      .reason(null)
                      .oldState(oldState)
                      .newState(newState)
                      .ipAddress(http != null ? http.getRemoteAddr() : null)
                      .userAgent(http != null ? http.getHeader("User-Agent") : null)
                      .build()));
    } catch (RuntimeException ex) {
      log.warn("admin_action_log write failed for {} {}: {}", type, entityId, ex.getMessage());
    }
  }
}
