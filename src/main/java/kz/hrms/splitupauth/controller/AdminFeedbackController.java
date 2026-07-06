package kz.hrms.splitupauth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.AdminFeedbackDto;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.dto.UpdateFeedbackRequest;
import kz.hrms.splitupauth.entity.FeedbackStatus;
import kz.hrms.splitupauth.entity.FeedbackType;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.FeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/feedback")
@RequiredArgsConstructor
public class AdminFeedbackController {

  private final FeedbackService feedbackService;

  @GetMapping
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<PagedResponse<AdminFeedbackDto>> list(
      @RequestParam(required = false) FeedbackType type,
      @RequestParam(required = false) FeedbackStatus status,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ResponseEntity.ok(feedbackService.adminList(type, status, q, page, size));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<AdminFeedbackDto> get(@PathVariable Long id) {
    return ResponseEntity.ok(feedbackService.adminGet(id));
  }

  @PatchMapping("/{id}")
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<AdminFeedbackDto> update(
      @AuthenticationPrincipal User admin,
      @PathVariable Long id,
      @Valid @RequestBody UpdateFeedbackRequest request,
      HttpServletRequest http) {
    return ResponseEntity.ok(feedbackService.adminUpdate(admin, id, request, http));
  }
}
