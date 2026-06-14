package kz.hrms.splitupauth.controller;

import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.CreateServiceReviewRequest;
import kz.hrms.splitupauth.dto.PublicServiceReviewDto;
import kz.hrms.splitupauth.dto.ServiceReviewDto;
import kz.hrms.splitupauth.dto.UpdateServiceReviewRequest;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.ServiceReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/service-reviews")
@RequiredArgsConstructor
public class ServiceReviewController {

    private final ServiceReviewService service;

    @GetMapping("/featured")
    public ResponseEntity<List<PublicServiceReviewDto>> getFeatured() {
        return ResponseEntity.ok(service.getFeatured());
    }

    @GetMapping("/me")
    public ResponseEntity<ServiceReviewDto> getMine(@AuthenticationPrincipal User user) {
        Optional<ServiceReviewDto> mine = service.getMine(user);
        return mine.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping
    public ResponseEntity<ServiceReviewDto> create(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CreateServiceReviewRequest req
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createMine(user, req));
    }

    @PutMapping("/me")
    public ResponseEntity<ServiceReviewDto> updateMine(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody UpdateServiceReviewRequest req
    ) {
        return ResponseEntity.ok(service.updateMine(user, req));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteMine(@AuthenticationPrincipal User user) {
        service.deleteMine(user);
        return ResponseEntity.noContent().build();
    }
}
