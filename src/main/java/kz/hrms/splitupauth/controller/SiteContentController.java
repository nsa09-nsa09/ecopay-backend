package kz.hrms.splitupauth.controller;

import kz.hrms.splitupauth.dto.SiteContentDto;
import kz.hrms.splitupauth.service.SiteContentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * Returns every language variant by default; the FE picks one based on the
     * active UI locale. {@code ?lang=kz|ru|en} is a convenience for callers
     * that prefer the legacy single-field shape — when set, the legacy
     * {title,mission,description} slots are overridden with the chosen
     * language's copy. All per-language fields stay in the payload either way.
     */
    @GetMapping("/about")
    public ResponseEntity<SiteContentDto> getAbout(
            @RequestParam(required = false) String lang
    ) {
        SiteContentDto dto = service.getAbout();
        if (lang != null && !lang.isBlank()) {
            dto.preferLanguage(lang);
        }
        return ResponseEntity.ok(dto);
    }
}
