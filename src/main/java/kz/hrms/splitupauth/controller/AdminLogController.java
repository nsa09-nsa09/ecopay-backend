package kz.hrms.splitupauth.controller;

import kz.hrms.splitupauth.dto.*;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.AdminLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/logs")
@RequiredArgsConstructor
public class AdminLogController {

    private final AdminLogService adminLogService;

    @GetMapping("/admin-actions")
    public ResponseEntity<PageResponse<AdminActionLogDto>> getAdminActionLogs(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime dateFrom,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime dateTo
    ) {
        AdminActionLogFilterRequest filter = new AdminActionLogFilterRequest();
        filter.setEntityType(entityType);
        filter.setActorUserId(actorUserId);
        filter.setDateFrom(dateFrom);
        filter.setDateTo(dateTo);

        return ResponseEntity.ok(adminLogService.getAdminActionLogsPaged(user, filter, page, size));
    }
    @GetMapping("/room-events")
    public ResponseEntity<PageResponse<RoomEventLogDto>> getRoomEventLogs(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long roomId,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime dateFrom,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime dateTo
    ) {
        RoomEventLogFilterRequest filter = new RoomEventLogFilterRequest();
        filter.setRoomId(roomId);
        filter.setActorUserId(actorUserId);
        filter.setEventType(eventType);
        filter.setDateFrom(dateFrom);
        filter.setDateTo(dateTo);

        return ResponseEntity.ok(adminLogService.getRoomEventLogsPaged(user, filter, page, size));
    }

    @GetMapping("/room-events/room/{roomId}")
    public ResponseEntity<List<RoomEventLogDto>> getRoomEventLogsByRoom(
            @PathVariable Long roomId,
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(adminLogService.getRoomEventLogsByRoom(roomId, user));
    }
}