package kz.hrms.splitupauth.controller;

import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.CreateRefundRequest;
import kz.hrms.splitupauth.dto.RefundTransactionResponse;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/refunds")
@RequiredArgsConstructor
public class RefundController {

    private final RefundService refundService;

    @PostMapping
    public ResponseEntity<RefundTransactionResponse> request(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CreateRefundRequest body
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(refundService.requestRefund(user, body));
    }

    @GetMapping("/me")
    public ResponseEntity<List<RefundTransactionResponse>> listMine(
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(refundService.listMine(user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RefundTransactionResponse> getOne(
            @AuthenticationPrincipal User user,
            @PathVariable Long id
    ) {
        return ResponseEntity.ok(refundService.getMine(user, id));
    }
}
