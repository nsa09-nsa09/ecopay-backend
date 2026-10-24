package kz.hrms.splitupauth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.*;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserReportCategory;
import kz.hrms.splitupauth.entity.UserReportStatus;
import kz.hrms.splitupauth.service.UserReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/user-reports")
@RequiredArgsConstructor
public class AdminUserReportController {
  private final UserReportService service;

  @GetMapping
  public PagedResponse<UserReportDto> list(
      @RequestParam(required = false) UserReportStatus status,
      @RequestParam(required = false) UserReportCategory category,
      @RequestParam(required = false) Long targetUserId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.list(status, category, targetUserId, page, size);
  }

  @GetMapping("/{reportId}")
  public UserReportDto get(@PathVariable Long reportId) {
    return service.get(reportId);
  }

  @PatchMapping("/{reportId}/assign")
  public UserReportDto assign(
      @PathVariable Long reportId,
      @RequestBody(required = false) UserReportAssignRequest body,
      @AuthenticationPrincipal User admin,
      HttpServletRequest request) {
    return service.assign(reportId, body == null ? null : body.adminId(), admin, request);
  }

  @PatchMapping("/{reportId}/status")
  public UserReportDto status(
      @PathVariable Long reportId,
      @Valid @RequestBody UserReportStatusRequest body,
      @AuthenticationPrincipal User admin,
      HttpServletRequest request) {
    return service.changeStatus(reportId, body, admin, request);
  }
}
