package kz.hrms.splitupauth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.AdminServiceReviewDto;
import kz.hrms.splitupauth.dto.AdminUpdateServiceReviewRequest;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.dto.SetFeaturedRequest;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.ServiceReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/service-reviews")
@RequiredArgsConstructor
public class AdminServiceReviewController {

    private final ServiceReviewService service;

    @GetMapping
    public ResponseEntity<PagedResponse<AdminServiceReviewDto>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Boolean featured
    ) {
        return ResponseEntity.ok(service.listForAdmin(page, size, featured));
    }

    @PatchMapping("/{id}/featured")
    public ResponseEntity<AdminServiceReviewDto> setFeatured(
            @PathVariable Long id,
            @AuthenticationPrincipal User admin,
            @Valid @RequestBody SetFeaturedRequest req,
            HttpServletRequest http
    ) {
        return ResponseEntity.ok(service.setFeatured(id, req.getFeatured(), admin, http));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AdminServiceReviewDto> update(
            @PathVariable Long id,
            @AuthenticationPrincipal User admin,
            @Valid @RequestBody AdminUpdateServiceReviewRequest req,
            HttpServletRequest http
    ) {
        return ResponseEntity.ok(service.adminUpdate(id, req, admin, http));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal User admin,
            HttpServletRequest http
    ) {
        service.adminDelete(id, admin, http);
        return ResponseEntity.noContent().build();
    }
}
