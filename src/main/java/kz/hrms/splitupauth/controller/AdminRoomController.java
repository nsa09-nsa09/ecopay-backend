package kz.hrms.splitupauth.controller;

import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.dto.RoomFilter;
import kz.hrms.splitupauth.dto.RoomSummaryDto;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.RoomType;
import kz.hrms.splitupauth.service.RoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/rooms")
@RequiredArgsConstructor
public class AdminRoomController {

    private final RoomService roomService;

    @GetMapping
    public ResponseEntity<PagedResponse<RoomSummaryDto>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) RoomStatus status,
            @RequestParam(required = false) RoomType roomType,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long serviceId,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir
    ) {
        RoomFilter filter = RoomFilter.builder()
                .status(status)
                .roomType(roomType)
                .categoryId(categoryId)
                .serviceId(serviceId)
                .build();
        return ResponseEntity.ok(
                roomService.getRooms(page, size, filter, sortBy, sortDir)
        );
    }
}
