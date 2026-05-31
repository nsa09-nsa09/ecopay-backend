package kz.hrms.splitupauth.controller;

import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.CreateReviewRequest;
import kz.hrms.splitupauth.dto.ReviewDto;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @PostMapping
    public ResponseEntity<ReviewDto> create(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CreateReviewRequest body
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reviewService.createReview(user, body));
    }
}
