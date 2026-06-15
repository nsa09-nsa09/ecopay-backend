package kz.hrms.splitupauth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.SiteContentDto;
import kz.hrms.splitupauth.dto.UpdateSiteContentRequest;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.SiteContentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/site")
@RequiredArgsConstructor
public class AdminSiteContentController {

    private final SiteContentService service;

    @GetMapping("/about")
    public ResponseEntity<SiteContentDto> getAbout() {
        return ResponseEntity.ok(service.getAbout());
    }

    @PutMapping("/about")
    public ResponseEntity<SiteContentDto> updateAbout(
            @AuthenticationPrincipal User admin,
            @Valid @RequestBody UpdateSiteContentRequest request,
            HttpServletRequest httpRequest
    ) {
        return ResponseEntity.ok(service.updateAbout(admin, request, httpRequest));
    }
}
