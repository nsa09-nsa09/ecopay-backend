package kz.hrms.splitupauth.controller;

import jakarta.validation.Valid;
import java.util.List;
import kz.hrms.splitupauth.dto.CreateDisputeStatementRequest;
import kz.hrms.splitupauth.dto.DisputeResponse;
import kz.hrms.splitupauth.dto.DisputeStatementDto;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.DisputeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/disputes")
@RequiredArgsConstructor
public class DisputeController {

  private final DisputeService disputeService;

  @GetMapping
  public ResponseEntity<List<DisputeResponse>> getMyDisputes(@AuthenticationPrincipal User user) {
    return ResponseEntity.ok(disputeService.getMyDisputes(user));
  }

  @GetMapping("/{disputeId}")
  public ResponseEntity<DisputeResponse> getPartyDispute(
      @PathVariable Long disputeId, @AuthenticationPrincipal User user) {
    return ResponseEntity.ok(disputeService.getPartyDispute(disputeId, user));
  }

  @PostMapping("/{disputeId}/statements")
  public ResponseEntity<DisputeStatementDto> addStatement(
      @PathVariable Long disputeId,
      @AuthenticationPrincipal User user,
      @Valid @RequestBody CreateDisputeStatementRequest request) {
    return ResponseEntity.ok(disputeService.addStatement(disputeId, user, request));
  }
}
