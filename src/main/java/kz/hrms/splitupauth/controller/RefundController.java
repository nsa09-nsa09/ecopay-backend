package kz.hrms.splitupauth.controller;

import java.util.List;
import kz.hrms.splitupauth.dto.RefundTransactionResponse;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/refunds")
@RequiredArgsConstructor
public class RefundController {

  private final RefundService refundService;

  @GetMapping("/me")
  public ResponseEntity<List<RefundTransactionResponse>> listMine(
      @AuthenticationPrincipal User user) {
    return ResponseEntity.ok(refundService.listMine(user));
  }

  @GetMapping("/{id}")
  public ResponseEntity<RefundTransactionResponse> getOne(
      @AuthenticationPrincipal User user, @PathVariable Long id) {
    return ResponseEntity.ok(refundService.getMine(user, id));
  }
}
