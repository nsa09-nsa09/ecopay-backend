package kz.hrms.splitupauth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.DisputeDecisionRequest;
import kz.hrms.splitupauth.dto.DisputeResponse;
import kz.hrms.splitupauth.dto.PageResponse;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.DisputeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import kz.hrms.splitupauth.dto.ApplyDisputeSanctionsRequest;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/disputes")
@RequiredArgsConstructor
public class AdminDisputeController {

    private final DisputeService disputeService;

    @GetMapping
    public ResponseEntity<PageResponse<DisputeResponse>> getQueue(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(disputeService.getAdminQueuePaged(user, page, size));
    }

    @GetMapping("/{disputeId}")
    public ResponseEntity<DisputeResponse> getOne(
            @PathVariable Long disputeId,
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(disputeService.getAdminDispute(disputeId, user));
    }

    @PatchMapping("/{disputeId}/assign")
    public ResponseEntity<DisputeResponse> assignToMe(
            @PathVariable Long disputeId,
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(disputeService.assignToMe(disputeId, user));
    }

    @PatchMapping("/{disputeId}/decision")
    public ResponseEntity<DisputeResponse> decide(
            @PathVariable Long disputeId,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody DisputeDecisionRequest request,
            HttpServletRequest httpRequest
    ) {
        return ResponseEntity.ok(disputeService.decide(disputeId, user, request, httpRequest));
    }
    @PostMapping("/{disputeId}/sanctions/owner-violation")
    public ResponseEntity<DisputeResponse> applyOwnerViolationSanctions(
            @PathVariable Long disputeId,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody ApplyDisputeSanctionsRequest request,
            HttpServletRequest httpRequest
    ) {
        return ResponseEntity.ok(
                disputeService.applyOwnerViolationSanctions(disputeId, user, request, httpRequest)
        );
    }
}