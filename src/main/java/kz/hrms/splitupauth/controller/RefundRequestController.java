package kz.hrms.splitupauth.controller;

import jakarta.validation.Valid;
import java.util.List;
import kz.hrms.splitupauth.dto.CreateRefundClaimRequest;
import kz.hrms.splitupauth.dto.RefundRequestResponse;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.RefundRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/refund-requests")
@RequiredArgsConstructor
public class RefundRequestController {

  private final RefundRequestService refundRequestService;

  @PostMapping
  public ResponseEntity<RefundRequestResponse> create(
      @AuthenticationPrincipal User user, @Valid @RequestBody CreateRefundClaimRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(refundRequestService.create(user, request));
  }

  @GetMapping("/me")
  public ResponseEntity<List<RefundRequestResponse>> listMine(@AuthenticationPrincipal User user) {
    return ResponseEntity.ok(refundRequestService.listMine(user));
  }
}
