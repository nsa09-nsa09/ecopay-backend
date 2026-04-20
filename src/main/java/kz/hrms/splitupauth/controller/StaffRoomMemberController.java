package kz.hrms.splitupauth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.RevealIdentifierRequest;
import kz.hrms.splitupauth.dto.RevealedIdentifierDto;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.RoomMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/staff/rooms")
@RequiredArgsConstructor
public class StaffRoomMemberController {

    private final RoomMemberService roomMemberService;

    @PostMapping("/{roomId}/members/{memberId}/reveal-identifier")
    public ResponseEntity<RevealedIdentifierDto> revealIdentifierForStaff(
            @PathVariable Long roomId,
            @PathVariable Long memberId,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody RevealIdentifierRequest request,
            HttpServletRequest httpRequest
    ) {
        return ResponseEntity.ok(
                roomMemberService.revealIdentifierForStaff(roomId, memberId, user, request, httpRequest)
        );
    }
}