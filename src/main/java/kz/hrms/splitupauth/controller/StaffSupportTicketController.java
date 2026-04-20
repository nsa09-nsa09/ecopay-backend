package kz.hrms.splitupauth.controller;

import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.CreateSupportMessageRequest;
import kz.hrms.splitupauth.dto.PageResponse;
import kz.hrms.splitupauth.dto.SupportTicketResponse;
import kz.hrms.splitupauth.dto.UpdateSupportTicketStatusRequest;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.SupportTicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/staff/support-tickets")
@RequiredArgsConstructor
public class StaffSupportTicketController {

    private final SupportTicketService supportTicketService;

//    @GetMapping("/queue")
//    public ResponseEntity<List<SupportTicketResponse>> getQueue(
//            @AuthenticationPrincipal User user
//    ) {
//        return ResponseEntity.ok(supportTicketService.getStaffQueue(user));
//    }
    @GetMapping("/queue")
    public ResponseEntity<PageResponse<SupportTicketResponse>> getQueue(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(supportTicketService.getStaffQueuePaged(user, page, size));
    }

//    @GetMapping("/assigned")
//    public ResponseEntity<List<SupportTicketResponse>> getAssignedToMe(
//            @AuthenticationPrincipal User user
//    ) {
//        return ResponseEntity.ok(supportTicketService.getAssignedTickets(user));
//    }
    @GetMapping("/assigned")
    public ResponseEntity<PageResponse<SupportTicketResponse>> getAssignedToMe(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(supportTicketService.getAssignedTicketsPaged(user, page, size));
    }
    @GetMapping("/{ticketId}")
    public ResponseEntity<SupportTicketResponse> getTicket(
            @PathVariable Long ticketId,
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(supportTicketService.getStaffTicket(ticketId, user));
    }

    @PostMapping("/{ticketId}/assign-to-me")
    public ResponseEntity<SupportTicketResponse> assignToMe(
            @PathVariable Long ticketId,
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(supportTicketService.assignTicketToMe(ticketId, user));
    }

    @PostMapping("/{ticketId}/status")
    public ResponseEntity<SupportTicketResponse> updateStatus(
            @PathVariable Long ticketId,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody UpdateSupportTicketStatusRequest request
    ) {
        return ResponseEntity.ok(supportTicketService.updateTicketStatus(ticketId, user, request));
    }

    @PostMapping("/{ticketId}/messages")
    public ResponseEntity<SupportTicketResponse> addStaffMessage(
            @PathVariable Long ticketId,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CreateSupportMessageRequest request
    ) {
        return ResponseEntity.ok(supportTicketService.addStaffMessage(ticketId, user, request));
    }

    @PostMapping("/{ticketId}/escalate")
    public ResponseEntity<SupportTicketResponse> escalateToAdmin(
            @PathVariable Long ticketId,
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(supportTicketService.escalateTicketToAdmin(ticketId, user));
    }
}