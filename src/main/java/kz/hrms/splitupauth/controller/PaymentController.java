package kz.hrms.splitupauth.controller;

import jakarta.validation.Valid;
import java.time.LocalDateTime;
import kz.hrms.splitupauth.dto.ConfirmPaymentRequest;
import kz.hrms.splitupauth.dto.CreatePaymentIntentRequest;
import kz.hrms.splitupauth.dto.PageResponse;
import kz.hrms.splitupauth.dto.PaymentHistoryItemDto;
import kz.hrms.splitupauth.dto.PaymentIntentResponse;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.PaymentHistoryService;
import kz.hrms.splitupauth.service.PaymentService;
import lombok.RequiredArgsConstructor;
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

  @PostMapping("/members/{roomMemberId}/intent")
  public ResponseEntity<PaymentIntentResponse> createPaymentIntent(
      @PathVariable Long roomMemberId,
      @AuthenticationPrincipal User user,
      @Valid @RequestBody CreatePaymentIntentRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(paymentService.createPaymentIntent(roomMemberId, user, request));
  }

  @GetMapping("/intents/{paymentIntentId}")
  public ResponseEntity<PaymentIntentResponse> getPaymentIntent(
      @PathVariable Long paymentIntentId, @AuthenticationPrincipal User user) {
    return ResponseEntity.ok(paymentService.getPaymentIntent(paymentIntentId, user));
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
    return ResponseEntity.ok(paymentService.confirmPaymentSuccess(paymentIntentId, user, request));
  }
}
