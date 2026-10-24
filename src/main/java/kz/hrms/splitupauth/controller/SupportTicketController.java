package kz.hrms.splitupauth.controller;

import jakarta.validation.Valid;
import java.util.List;
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

@RestController
@RequestMapping("/api/v1/support-tickets")
@RequiredArgsConstructor
public class SupportTicketController {

  private final SupportTicketService supportTicketService;
  private final kz.hrms.splitupauth.service.RateLimiter rateLimiter;

  // B15: anti-flood limits for support actions. Burst-focused (per-minute) and sustained
  // (per-hour) buckets are deliberate — a single aggrieved user can send ten messages in a
  // minute while nobody can post two hundred in an hour.
  @org.springframework.beans.factory.annotation.Value(
      "${app.rate-limit.support-message.per-minute-max:10}")
  private int supportMessagePerMinuteMax;

  @org.springframework.beans.factory.annotation.Value(
      "${app.rate-limit.support-message.per-hour-max:100}")
  private int supportMessagePerHourMax;

  @org.springframework.beans.factory.annotation.Value(
      "${app.rate-limit.support-ticket.per-hour-max:5}")
  private int supportTicketPerHourMax;

  private static final String RATE_LIMIT_MESSAGE = "Слишком много сообщений. Подождите минуту";

  @PostMapping
  public ResponseEntity<SupportTicketResponse> createTicket(
      @AuthenticationPrincipal User user, @Valid @RequestBody CreateSupportTicketRequest request) {
    if (supportTicketPerHourMax > 0) {
      rateLimiter.check(
          "support-ticket:create:" + user.getId(),
          supportTicketPerHourMax,
          3600,
          RATE_LIMIT_MESSAGE);
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(supportTicketService.createTicket(user, request));
  }

  @GetMapping
  public ResponseEntity<List<SupportTicketResponse>> getMyTickets(
      @AuthenticationPrincipal User user) {
    return ResponseEntity.ok(supportTicketService.getMyTickets(user));
  }

  @GetMapping("/{ticketId}")
  public ResponseEntity<SupportTicketResponse> getMyTicket(
      @PathVariable Long ticketId, @AuthenticationPrincipal User user) {
    return ResponseEntity.ok(supportTicketService.getMyTicket(ticketId, user));
  }

  @PostMapping("/{ticketId}/messages")
  public ResponseEntity<SupportTicketResponse> addMessage(
      @PathVariable Long ticketId,
      @AuthenticationPrincipal User user,
      @Valid @RequestBody CreateSupportMessageRequest request) {
    if (supportMessagePerMinuteMax > 0) {
      rateLimiter.check(
          "support-message:min:" + user.getId(),
          supportMessagePerMinuteMax,
          60,
          RATE_LIMIT_MESSAGE);
    }
    if (supportMessagePerHourMax > 0) {
      rateLimiter.check(
          "support-message:hour:" + user.getId(),
          supportMessagePerHourMax,
          3600,
          RATE_LIMIT_MESSAGE);
    }
    return ResponseEntity.ok(supportTicketService.addMessage(ticketId, user, request));
  }
}
