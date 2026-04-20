package kz.hrms.splitupauth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.CreateRefundRequest;
import kz.hrms.splitupauth.dto.RefundTransactionResponse;
import kz.hrms.splitupauth.dto.UpdateRefundStatusRequest;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/refunds")
@RequiredArgsConstructor
public class AdminRefundController {

    private final RefundService refundService;

    @PostMapping
    public ResponseEntity<RefundTransactionResponse> create(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CreateRefundRequest request,
            HttpServletRequest httpRequest
    ) {
        return ResponseEntity.ok(refundService.createRefund(user, request, httpRequest));
    }

    @GetMapping("/by-dispute/{disputeId}")
    public ResponseEntity<List<RefundTransactionResponse>> getByDispute(
            @PathVariable Long disputeId,
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(refundService.getRefundsByDispute(disputeId, user));
    }

    @PatchMapping("/{refundId}/success")
    public ResponseEntity<RefundTransactionResponse> markSuccess(
            @PathVariable Long refundId,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody UpdateRefundStatusRequest request,
            HttpServletRequest httpRequest
    ) {
        return ResponseEntity.ok(refundService.markSuccess(refundId, user, request, httpRequest));
    }

    @PatchMapping("/{refundId}/fail")
    public ResponseEntity<RefundTransactionResponse> markFailed(
            @PathVariable Long refundId,
            @AuthenticationPrincipal User user,
            HttpServletRequest httpRequest
    ) {
        return ResponseEntity.ok(refundService.markFailed(refundId, user, httpRequest));
    }
}