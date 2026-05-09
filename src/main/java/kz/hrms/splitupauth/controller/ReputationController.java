package kz.hrms.splitupauth.controller;

import kz.hrms.splitupauth.dto.ReputationDto;
import kz.hrms.splitupauth.dto.ReviewDto;
import kz.hrms.splitupauth.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reputation")
@RequiredArgsConstructor
public class ReputationController {

    private final ReviewService reviewService;

    @GetMapping("/users/{userId}")
    public ResponseEntity<ReputationDto> getUser(@PathVariable Long userId) {
        return ResponseEntity.ok(reviewService.getReputation(userId));
    }

    @GetMapping("/users/{userId}/reviews")
    public ResponseEntity<List<ReviewDto>> getReviews(@PathVariable Long userId) {
        return ResponseEntity.ok(reviewService.listForRecipient(userId));
    }
}
