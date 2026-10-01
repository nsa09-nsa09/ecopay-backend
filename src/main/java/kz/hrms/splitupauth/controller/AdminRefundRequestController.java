package kz.hrms.splitupauth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import kz.hrms.splitupauth.dto.ApproveRefundClaimRequest;
import kz.hrms.splitupauth.dto.RefundRequestResponse;
import kz.hrms.splitupauth.dto.RejectRefundClaimRequest;
import kz.hrms.splitupauth.entity.RefundRequestStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.RefundRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/refund-requests")
@RequiredArgsConstructor
public class AdminRefundRequestController {

  private final RefundRequestService refundRequestService;

  @GetMapping
  public ResponseEntity<List<RefundRequestResponse>> list(
      @AuthenticationPrincipal User user,
      @RequestParam(required = false) RefundRequestStatus status) {
    return ResponseEntity.ok(refundRequestService.listForAdmin(user, status));
  }

  @PostMapping("/{requestId}/approve")
  public ResponseEntity<RefundRequestResponse> approve(
      @PathVariable Long requestId,
      @AuthenticationPrincipal User user,
      @Valid @RequestBody ApproveRefundClaimRequest request,
      HttpServletRequest httpRequest) {
    return ResponseEntity.ok(refundRequestService.approve(requestId, user, request, httpRequest));
  }

  @PostMapping("/{requestId}/reject")
  public ResponseEntity<RefundRequestResponse> reject(
      @PathVariable Long requestId,
      @AuthenticationPrincipal User user,
      @Valid @RequestBody RejectRefundClaimRequest request,
      HttpServletRequest httpRequest) {
    return ResponseEntity.ok(refundRequestService.reject(requestId, user, request, httpRequest));
  }
}
