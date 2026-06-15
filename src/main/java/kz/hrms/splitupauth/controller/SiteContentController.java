package kz.hrms.splitupauth.controller;

import kz.hrms.splitupauth.dto.SiteContentDto;
import kz.hrms.splitupauth.service.SiteContentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public read-only access to admin-editable site content (currently the
 * "About Us" page). Mounted under /api/v1/site so it can be whitelisted in
 * SecurityConfig as permitAll.
 */
@RestController
@RequestMapping("/api/v1/site")
@RequiredArgsConstructor
public class SiteContentController {

    private final SiteContentService service;

    @GetMapping("/about")
    public ResponseEntity<SiteContentDto> getAbout() {
        return ResponseEntity.ok(service.getAbout());
    }
}
