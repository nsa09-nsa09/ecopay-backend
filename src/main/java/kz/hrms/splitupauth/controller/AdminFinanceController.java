package kz.hrms.splitupauth.controller;

import java.time.LocalDateTime;
import kz.hrms.splitupauth.dto.FinancePayoutDto;
import kz.hrms.splitupauth.dto.FinanceRefundDto;
import kz.hrms.splitupauth.dto.FinanceTransactionDto;
import kz.hrms.splitupauth.dto.FinanceWebhookDto;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.service.AdminFinanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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
