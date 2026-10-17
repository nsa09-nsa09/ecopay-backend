package kz.hrms.splitupauth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.VisitRequest;
import kz.hrms.splitupauth.dto.VisitResponse;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.SiteVisitService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

  private final SiteVisitService siteVisitService;

  /**
   * Public ping called by the SPA on each navigation. Issues the HttpOnly "vid" cookie on the first
   * hit and dedupes additional hits the same day. If the caller is logged-in, the visit row is
   * attributed to the user.
   */
  @PostMapping("/visit")
  public ResponseEntity<VisitResponse> recordVisit(
      @RequestBody(required = false) @Valid VisitRequest body,
      HttpServletRequest request,
      HttpServletResponse response,
      @AuthenticationPrincipal User user) {
    String path = body == null ? null : body.getPath();
    SiteVisitService.VisitResult result =
        siteVisitService.recordVisit(request, response, path, user);
    return ResponseEntity.ok(
        VisitResponse.builder()
            .visitorId(result.visitorId())
            .newVisitorToday(result.newVisitorToday())
            .build());
  }
}
