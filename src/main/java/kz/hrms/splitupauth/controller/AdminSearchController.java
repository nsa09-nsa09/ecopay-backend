package kz.hrms.splitupauth.controller;

import kz.hrms.splitupauth.dto.AdminSearchResultDto;
import kz.hrms.splitupauth.service.AdminSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Top-bar "spotlight" search for the admin shell. Hits three sections in
 * parallel (rooms / users / feedback) and returns small grouped lists so the
 * dropdown can render each section under its own heading. Under
 * {@code /api/v1/admin/**} which {@code SecurityConfig} restricts to ADMIN;
 * the {@code @PreAuthorize} below is the same gate restated at the method
 * level — a defensive duplication so a future Security config edit can't
 * accidentally widen the surface.
 */
@RestController
@RequestMapping("/api/v1/admin/search")
@RequiredArgsConstructor
public class AdminSearchController {

    private final AdminSearchService searchService;

    @GetMapping
    @PreAuthorize("hasAuthority('ADMIN')")
    public ResponseEntity<AdminSearchResultDto> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit
    ) {
        return ResponseEntity.ok(searchService.search(q, limit));
    }
}
