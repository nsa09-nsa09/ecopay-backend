package kz.hrms.splitupauth.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import kz.hrms.splitupauth.dto.RefundTransactionResponse;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/refunds")
@RequiredArgsConstructor
public class AdminRefundController {

  private final RefundService refundService;

  @GetMapping("/by-dispute/{disputeId}")
  public ResponseEntity<List<RefundTransactionResponse>> getByDispute(
      @PathVariable Long disputeId, @AuthenticationPrincipal User user) {
    return ResponseEntity.ok(refundService.getRefundsByDispute(disputeId, user));
  }

  @PatchMapping("/{refundId}/fail")
  public ResponseEntity<RefundTransactionResponse> markFailed(
      @PathVariable Long refundId,
      @AuthenticationPrincipal User user,
      HttpServletRequest httpRequest) {
    return ResponseEntity.ok(refundService.markFailed(refundId, user, httpRequest));
  }
}
