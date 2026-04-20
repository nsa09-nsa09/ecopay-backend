package kz.hrms.splitupauth.controller;

import kz.hrms.splitupauth.dto.DisputeResponse;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.DisputeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/disputes")
@RequiredArgsConstructor
public class DisputeController {

    private final DisputeService disputeService;

    @GetMapping
    public ResponseEntity<List<DisputeResponse>> getMyDisputes(
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(disputeService.getMyDisputes(user));
    }

    @GetMapping("/{disputeId}")
    public ResponseEntity<DisputeResponse> getMyDispute(
            @PathVariable Long disputeId,
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(disputeService.getMyDispute(disputeId, user));
    }
}