package kz.hrms.splitupauth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.AdminDecisionRequest;
import kz.hrms.splitupauth.dto.BatchConfirmRequest;
import kz.hrms.splitupauth.dto.ModerationQueueItemDto;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.ModerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/moderation")
@RequiredArgsConstructor
public class AdminModerationController {

    private final ModerationService moderationService;

    @GetMapping("/queue")
    public ResponseEntity<List<ModerationQueueItemDto>> getOpenQueue(
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(moderationService.getOpenQueue(user));
    }

    @PatchMapping("/queue/{queueId}/assign")
    public ResponseEntity<ModerationQueueItemDto> assignToMe(
            @PathVariable Long queueId,
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(moderationService.assignToMe(queueId, user));
    }

    @PatchMapping("/queue/{queueId}/confirm")
    public ResponseEntity<ModerationQueueItemDto> confirmMembership(
            @PathVariable Long queueId,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody AdminDecisionRequest request,
            HttpServletRequest httpRequest
    ) {
        return ResponseEntity.ok(
                moderationService.confirmMembership(queueId, user, request, httpRequest)
        );
    }

    @PatchMapping("/queue/{queueId}/reject")
    public ResponseEntity<ModerationQueueItemDto> rejectMembership(
            @PathVariable Long queueId,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody AdminDecisionRequest request,
            HttpServletRequest httpRequest
    ) {
        return ResponseEntity.ok(
                moderationService.rejectMembership(queueId, user, request, httpRequest)
        );
    }

    @PatchMapping("/rooms/{roomId}/block")
    public ResponseEntity<Void> blockRoom(
            @PathVariable Long roomId,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody AdminDecisionRequest request,
            HttpServletRequest httpRequest
    ) {
        moderationService.blockRoom(roomId, user, request, httpRequest);
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/queue/batch-confirm")
    public ResponseEntity<List<ModerationQueueItemDto>> batchConfirm(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody BatchConfirmRequest request,
            HttpServletRequest httpRequest
    ) {
        return ResponseEntity.ok(
                moderationService.batchConfirmMemberships(user, request, httpRequest)
        );
    }
    @PatchMapping("/users/{userId}/ban")
    public ResponseEntity<Void> banUser(
            @PathVariable Long userId,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody AdminDecisionRequest request,
            HttpServletRequest httpRequest
    ) {
        moderationService.banUser(userId, user, request, httpRequest);
        return ResponseEntity.noContent().build();
    }
}