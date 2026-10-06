package kz.hrms.splitupauth.controller;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.UUID;
import kz.hrms.splitupauth.dto.FinancePayoutDto;
import kz.hrms.splitupauth.dto.FinanceRefundDto;
import kz.hrms.splitupauth.dto.FinanceTransactionDto;
import kz.hrms.splitupauth.dto.FinanceWebhookDto;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.entity.AdminActionLog;
import kz.hrms.splitupauth.entity.AdminActionType;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.ResourceConflictException;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.service.AdminFinanceService;
import kz.hrms.splitupauth.service.FreedomWebhookInboxCoordinator;
import kz.hrms.splitupauth.service.FreedomWebhookInboxTransactions;
import kz.hrms.splitupauth.util.ClientIp;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Drill-down endpoints backing the admin dashboard "Финансы" cards. Each card on /admin/dashboard
 * links here with a preselected tab; the controller returns row-level transaction / refund data so
 * the operator can see "who / what / when" behind the KPI totals.
 *
 * <p>All routes sit under {@code /api/v1/admin/**} which SecurityConfig restricts to ADMIN; the
 * per-method {@link PreAuthorize} is defense-in-depth in case that config is ever loosened.
 */
@RestController
@RequestMapping("/api/v1/admin/finance")
@RequiredArgsConstructor
public class AdminFinanceController {

  private final AdminFinanceService financeService;
  private final FreedomWebhookInboxTransactions inboxTransactions;
  private final FreedomWebhookInboxCoordinator inboxCoordinator;
  private final AdminActionLogRepository adminActionLogRepository;

  /** Operator justification is mandatory for every manual money-path action. */
  public record RequeueWebhookRequest(@NotBlank @Size(max = 500) String reason) {}

  /**
   * Re-queues a DEAD_LETTER FreedomPay callback (e.g. after the referenced intent/binding was
   * repaired). Only rows whose signature verified can be re-queued; processing re-verifies the
   * signature and every money operation stays idempotent, so this can never double-apply money.
   * Audited in AdminActionLog with the operator's reason.
   */
  @PostMapping("/webhooks/{id}/requeue")
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<java.util.Map<String, Object>> requeueWebhook(
      @PathVariable Long id,
      @Valid @RequestBody RequeueWebhookRequest request,
      @AuthenticationPrincipal User admin,
      HttpServletRequest httpRequest) {
    String previousError = inboxTransactions.requeueDeadLetter(id);
    if (previousError == null) {
      throw new ResourceConflictException(
          "WEBHOOK_NOT_REQUEUEABLE",
          "Only dead-lettered callbacks with a valid signature can be re-queued");
    }
    adminActionLogRepository.save(
        AdminActionLog.builder()
            .eventId(UUID.randomUUID())
            .adminUser(admin)
            .actionType(AdminActionType.WEBHOOK_REQUEUED)
            .entityType("FREEDOM_WEBHOOK")
            .entityId(id)
            .reason(request.reason())
            .oldState(
                JsonNodeFactory.instance
                    .objectNode()
                    .put("processingStatus", "DEAD_LETTER")
                    .put("lastErrorCode", previousError))
            .newState(JsonNodeFactory.instance.objectNode().put("processingStatus", "PENDING"))
            .ipAddress(ClientIp.of(httpRequest))
            .userAgent(httpRequest.getHeader("User-Agent"))
            .build());
    inboxCoordinator.processInbox(id);
    return ResponseEntity.ok(java.util.Map.of("id", id, "requeued", true));
  }

  @GetMapping("/transactions")
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<PagedResponse<FinanceTransactionDto>> transactions(
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime dateFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime dateTo,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ResponseEntity.ok(
        financeService.listTransactions(type, status, dateFrom, dateTo, page, size));
  }

  @GetMapping("/refunds")
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<PagedResponse<FinanceRefundDto>> refunds(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime dateFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime dateTo,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ResponseEntity.ok(financeService.listRefunds(status, dateFrom, dateTo, page, size));
  }

  @GetMapping("/payouts")
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<PagedResponse<FinancePayoutDto>> payouts(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime dateFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime dateTo,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ResponseEntity.ok(financeService.listPayouts(status, dateFrom, dateTo, page, size));
  }

  @GetMapping("/webhooks")
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<PagedResponse<FinanceWebhookDto>> webhooks(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String script,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime dateFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime dateTo,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ResponseEntity.ok(
        financeService.listWebhooks(status, script, dateFrom, dateTo, page, size));
  }
}
