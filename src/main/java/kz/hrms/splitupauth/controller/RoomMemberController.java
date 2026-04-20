package kz.hrms.splitupauth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.*;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.RoomMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/rooms")
@RequiredArgsConstructor
public class RoomMemberController {

    private final RoomMemberService roomMemberService;

    @PostMapping("/{id}/members")
    public ResponseEntity<RoomMemberDto> createMembership(
            @PathVariable Long id,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody JoinRoomRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(roomMemberService.joinRoom(id, user, request));
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<PagedResponse<RoomMemberDto>> getRoomMembers(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(roomMemberService.getRoomMembers(id, page, size, user));
    }

    @GetMapping("/{id}/members/me")
    public ResponseEntity<MyRoomMembershipDto> getMyMembership(
            @PathVariable Long id,
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(roomMemberService.getMyMembership(id, user));
    }
    @PatchMapping("/{roomId}/members/{memberId}/owner-access")
    public ResponseEntity<RoomMemberDto> confirmOwnerAccess(
            @PathVariable Long roomId,
            @PathVariable Long memberId,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody ConfirmOwnerAccessRequest request
    ) {
        return ResponseEntity.ok(
                roomMemberService.confirmOwnerAccess(roomId, memberId, user, request)
        );
    }

    @PostMapping("/{roomId}/members/me/confirm-access")
    public ResponseEntity<MyRoomMembershipDto> confirmMemberAccess(
            @PathVariable Long roomId,
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(
                roomMemberService.confirmMemberAccess(roomId, user)
        );
    }
    @PostMapping("/{roomId}/members/{memberId}/reveal-identifier")
    public ResponseEntity<RevealedIdentifierDto> revealIdentifier(
            @PathVariable Long roomId,
            @PathVariable Long memberId,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody RevealIdentifierRequest request,
            HttpServletRequest httpRequest
    ) {
        return ResponseEntity.ok(
                roomMemberService.revealIdentifierForOwner(roomId, memberId, user, request, httpRequest)
        );
    }
}
