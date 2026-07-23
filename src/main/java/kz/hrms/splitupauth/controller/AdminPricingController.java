package kz.hrms.splitupauth.controller;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import kz.hrms.splitupauth.dto.CreatePriceWatchProviderRequest;
import kz.hrms.splitupauth.dto.PriceChangeDto;
import kz.hrms.splitupauth.dto.PriceSnapshotDto;
import kz.hrms.splitupauth.dto.PriceWatchProviderDto;
import kz.hrms.splitupauth.dto.TestPriceExtractionRequest;
import kz.hrms.splitupauth.dto.TestPriceExtractionResponse;
import kz.hrms.splitupauth.dto.UpdatePriceWatchProviderRequest;
import kz.hrms.splitupauth.pricing.AdminPricingService;
import kz.hrms.splitupauth.pricing.PriceWatchAdminRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for the Price Watch module. Inherits ADMIN authority from the {@code
 * /api/v1/admin/**} matcher in {@code SecurityConfig}; no per-method annotation needed.
 */
@RestController
@RequestMapping("/api/v1/admin/pricing")
@RequiredArgsConstructor
public class AdminPricingController {

  private final AdminPricingService adminPricingService;
  private final PriceWatchAdminRateLimiter rateLimiter;

  @GetMapping("/providers")
  public ResponseEntity<List<PriceWatchProviderDto>> listProviders() {
    return ResponseEntity.ok(adminPricingService.listProviders());
  }

  @PostMapping("/providers")
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<PriceWatchProviderDto> createProvider(
      @Valid @RequestBody CreatePriceWatchProviderRequest req, Principal principal) {
    rateLimiter.check(actor(principal), "create");
    return ResponseEntity.status(HttpStatus.CREATED).body(adminPricingService.createProvider(req));
  }

  @GetMapping("/providers/{id}")
  public ResponseEntity<PriceWatchProviderDto> getProvider(@PathVariable Long id) {
    return ResponseEntity.ok(adminPricingService.getProvider(id));
  }

  @PutMapping("/providers/{id}")
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<PriceWatchProviderDto> updateProvider(
      @PathVariable Long id,
      @Valid @RequestBody UpdatePriceWatchProviderRequest req,
      Principal principal) {
    rateLimiter.check(actor(principal), "update");
    return ResponseEntity.ok(adminPricingService.updateProvider(id, req));
  }

  @DeleteMapping("/providers/{id}")
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<Void> deleteProvider(@PathVariable Long id) {
    adminPricingService.deleteProvider(id);
    return ResponseEntity.noContent().build();
  }

  /**
   * Synchronous "check now" — runs one fetch/extract cycle and returns the fresh provider row so
   * the admin table can render the new price/status immediately. Bounded by {@code
   * app.pricing.timeout-seconds}. The old async firehose is still available via {@link
   * AdminPricingService#triggerCheck} for the scheduler.
   */
  @PostMapping("/providers/{id}/check")
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<PriceWatchProviderDto> checkProvider(
      @PathVariable Long id, Principal principal) {
    rateLimiter.check(actor(principal), "check");
    return ResponseEntity.ok(adminPricingService.checkNow(id));
  }

  /**
   * Dry-run an extractor recipe against a URL — no side effects. Powers the "Test URL" button in
   * the admin's Upsert modal so a bad selector/regex can be caught before the row is saved.
   */
  @PostMapping("/providers/test")
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<TestPriceExtractionResponse> testExtraction(
      @Valid @RequestBody TestPriceExtractionRequest req, Principal principal) {
    rateLimiter.check(actor(principal), "test");
    return ResponseEntity.ok(adminPricingService.testExtraction(req));
  }

  @GetMapping("/providers/{id}/history")
  public ResponseEntity<List<PriceSnapshotDto>> history(
      @PathVariable Long id,
      @RequestParam(name = "limit", required = false, defaultValue = "50") int limit) {
    return ResponseEntity.ok(adminPricingService.history(id, limit));
  }

  @GetMapping("/changes")
  public ResponseEntity<List<PriceChangeDto>> changes(
      @RequestParam(name = "unacknowledged", required = false, defaultValue = "false")
          boolean unacknowledged) {
    return ResponseEntity.ok(adminPricingService.changes(unacknowledged));
  }

  @PostMapping("/changes/{id}/ack")
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<PriceChangeDto> ackChange(@PathVariable Long id) {
    return ResponseEntity.ok(adminPricingService.acknowledgeChange(id));
  }

  private static String actor(Principal principal) {
    return principal == null ? "unknown" : principal.getName();
  }
}
