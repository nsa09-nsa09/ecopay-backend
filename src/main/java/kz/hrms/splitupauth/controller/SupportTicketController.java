package kz.hrms.splitupauth.controller;

import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.CreateSupportMessageRequest;
import kz.hrms.splitupauth.dto.CreateSupportTicketRequest;
import kz.hrms.splitupauth.dto.SupportTicketResponse;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.SupportTicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/support-tickets")
@RequiredArgsConstructor
public class SupportTicketController {

    private final SupportTicketService supportTicketService;

    @PostMapping
    public ResponseEntity<SupportTicketResponse> createTicket(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CreateSupportTicketRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(supportTicketService.createTicket(user, request));
    }

    @GetMapping
    public ResponseEntity<List<SupportTicketResponse>> getMyTickets(
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(supportTicketService.getMyTickets(user));
    }

    @GetMapping("/{ticketId}")
    public ResponseEntity<SupportTicketResponse> getMyTicket(
            @PathVariable Long ticketId,
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(supportTicketService.getMyTicket(ticketId, user));
    }

    @PostMapping("/{ticketId}/messages")
    public ResponseEntity<SupportTicketResponse> addMessage(
            @PathVariable Long ticketId,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CreateSupportMessageRequest request
    ) {
        return ResponseEntity.ok(supportTicketService.addMessage(ticketId, user, request));
    }
}