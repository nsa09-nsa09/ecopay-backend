package kz.hrms.splitupauth.controller;

import jakarta.validation.Valid;
import java.time.LocalDateTime;
import kz.hrms.splitupauth.dto.ConfirmPaymentRequest;
import kz.hrms.splitupauth.dto.CreatePaymentIntentRequest;
import kz.hrms.splitupauth.dto.PageResponse;
import kz.hrms.splitupauth.dto.PaymentHistoryItemDto;
import kz.hrms.splitupauth.dto.PaymentIntentResponse;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.TooManyRequestsException;
import kz.hrms.splitupauth.service.PaymentHistoryService;
import kz.hrms.splitupauth.service.PaymentService;
import kz.hrms.splitupauth.service.RateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

  private final PaymentService paymentService;
  private final PaymentHistoryService paymentHistoryService;
  private final RateLimiter rateLimiter;

  /** Max intent creations (initial + renewal) tolerated per user per window. */
  @Value("${app.rate-limit.payment-intent.max:10}")
  private int intentMax;

  @Value("${app.rate-limit.payment-intent.window-seconds:600}")
  private long intentWindowSeconds;

  /** confirm-success soft limits: calls still hit FreedomPay, which asks for 1.5–2 s spacing. */
  @Value("${app.rate-limit.payment-confirm.per-intent-window-seconds:3}")
  private long confirmPerIntentWindowSeconds;

  @Value("${app.rate-limit.payment-confirm.user-max-per-minute:20}")
  private int confirmUserMaxPerMinute;

  @PostMapping("/members/{roomMemberId}/intent")
  public ResponseEntity<PaymentIntentResponse> createPaymentIntent(
      @PathVariable Long roomMemberId,
      @AuthenticationPrincipal User user,
      @Valid @RequestBody CreatePaymentIntentRequest request) {
    rateLimitIntentCreation(user);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(paymentService.createPaymentIntent(roomMemberId, user, request));
  }

  /**
   * Shared by the initial and renewal intent endpoints: each intent reserves a seat for 30 minutes,
   * so a user who spams it is throttled with a hard 429 ({@code RATE_LIMITED}).
   */
  private void rateLimitIntentCreation(User user) {
    if (intentMax > 0) {
      rateLimiter.check(
          "payment-intent:" + user.getId(),
          intentMax,
          intentWindowSeconds,
          "Слишком много попыток оплаты. Попробуйте позже.");
    }
  }

  @GetMapping("/intents/{paymentIntentId}")
  public ResponseEntity<PaymentIntentResponse> getPaymentIntent(
      @PathVariable Long paymentIntentId, @AuthenticationPrincipal User user) {
    return ResponseEntity.ok(paymentService.getPaymentIntent(paymentIntentId, user));
  }

  @GetMapping("/members/{roomMemberId}/intent/current")
  public ResponseEntity<PaymentIntentResponse> getCurrentPaymentIntent(
      @PathVariable Long roomMemberId, @AuthenticationPrincipal User user) {
    return ResponseEntity.ok(paymentService.getCurrentPaymentIntentForMember(roomMemberId, user));
  }

  @GetMapping("/history")
  public ResponseEntity<PageResponse<PaymentHistoryItemDto>> history(
      @AuthenticationPrincipal User user,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime dateFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime dateTo) {
    return ResponseEntity.ok(
        paymentHistoryService.history(user, page, size, kind, status, dateFrom, dateTo));
  }

  @PostMapping("/intents/{paymentIntentId}/confirm-success")
  public ResponseEntity<PaymentIntentResponse> confirmPaymentSuccess(
      @PathVariable Long paymentIntentId,
      @AuthenticationPrincipal User user,
      @Valid @RequestBody ConfirmPaymentRequest request) {
    // Each confirm-success calls FreedomPay, which asks for 1.5–2 s between requests. Rather than
    // 429 (which would make the polling UI surface an error), an over-eager poll just gets the
    // current intent state from the DB — no provider call. Limits: ≤1 per 3 s per intent and
    // ≤20/min per user; the DB read still enforces ownership.
    if (isConfirmThrottled(paymentIntentId, user)) {
      return ResponseEntity.ok(paymentService.getPaymentIntent(paymentIntentId, user));
    }
    return ResponseEntity.ok(paymentService.confirmPaymentSuccess(paymentIntentId, user, request));
  }

  private boolean isConfirmThrottled(Long paymentIntentId, User user) {
    try {
      if (confirmUserMaxPerMinute > 0) {
        rateLimiter.check(
            "payment-confirm:user:" + user.getId(), confirmUserMaxPerMinute, 60, "throttled");
      }
      if (confirmPerIntentWindowSeconds > 0) {
        rateLimiter.check(
            "payment-confirm:intent:" + paymentIntentId,
            1,
            confirmPerIntentWindowSeconds,
            "throttled");
      }
      return false;
    } catch (TooManyRequestsException ex) {
      return true;
    }
  }
}
