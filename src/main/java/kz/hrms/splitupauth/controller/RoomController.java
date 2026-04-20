package kz.hrms.splitupauth.controller;

import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.*;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.RoomType;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.RoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import kz.hrms.splitupauth.dto.PagedResponse;


@RestController
@RequestMapping("/api/v1/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;

    @PostMapping
    public ResponseEntity<RoomResponse> createRoom(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CreateRoomRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(roomService.createRoom(user, request));
    }

    @GetMapping
    public ResponseEntity<PagedResponse<RoomSummaryDto>> getRooms(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) RoomStatus status,
            @RequestParam(required = false) RoomType roomType,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir
    ) {
        return ResponseEntity.ok(
                roomService.getRooms(page, size, status, roomType, categoryId, sortBy, sortDir)
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<RoomResponse> getRoom(@PathVariable Long id) {
        return ResponseEntity.ok(roomService.getRoom(id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<RoomResponse> updateRoom(
            @PathVariable Long id,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody UpdateRoomRequest request
    ) {
        return ResponseEntity.ok(roomService.updateRoom(id, user, request));
    }
    @PostMapping("/{id}/ready-for-verification")
    public ResponseEntity<RoomResponse> markReadyForVerification(
            @PathVariable Long id,
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(roomService.markReadyForVerification(id, user));
    }
    @PostMapping("/{id}/cancel")
    public ResponseEntity<RoomResponse> cancelRoom(
            @PathVariable Long id,
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(roomService.cancelRoom(id, user));
    }
    @PostMapping("/{id}/complete")
    public ResponseEntity<RoomResponse> completeRoom(
            @PathVariable Long id,
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(roomService.completeRoom(id, user));
    }

}
