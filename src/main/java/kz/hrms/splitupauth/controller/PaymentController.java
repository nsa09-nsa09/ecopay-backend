package kz.hrms.splitupauth.controller;

import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.ConfirmPaymentRequest;
import kz.hrms.splitupauth.dto.CreatePaymentIntentRequest;
import kz.hrms.splitupauth.dto.PaymentIntentResponse;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/members/{roomMemberId}/intent")
    public ResponseEntity<PaymentIntentResponse> createPaymentIntent(
            @PathVariable Long roomMemberId,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CreatePaymentIntentRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentService.createPaymentIntent(roomMemberId, user, request));
    }

    @PostMapping("/intents/{paymentIntentId}/confirm-success")
    public ResponseEntity<PaymentIntentResponse> confirmPaymentSuccess(
            @PathVariable Long paymentIntentId,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody ConfirmPaymentRequest request
    ) {
        return ResponseEntity.ok(
                paymentService.confirmPaymentSuccess(paymentIntentId, user, request)
        );
    }
}