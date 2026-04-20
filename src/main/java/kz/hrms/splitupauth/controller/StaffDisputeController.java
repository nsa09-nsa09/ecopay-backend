package kz.hrms.splitupauth.controller;

import kz.hrms.splitupauth.dto.DisputeResponse;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.DisputeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/staff/disputes")
@RequiredArgsConstructor
public class StaffDisputeController {

    private final DisputeService disputeService;

    @PostMapping("/from-ticket/{ticketId}")
    public ResponseEntity<DisputeResponse> openFromTicket(
            @PathVariable Long ticketId,
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(disputeService.openFromTicket(ticketId, user));
    }
}