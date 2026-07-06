package kz.hrms.splitupauth.controller;

import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.*;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.entity.AccessType;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.RoomType;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.InMemoryRateLimiter;
import kz.hrms.splitupauth.service.RoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/rooms")
@RequiredArgsConstructor
public class RoomController {

  private final RoomService roomService;
  private final InMemoryRateLimiter rateLimiter;

  /** Burst guard: max rooms one user may create within the short window. */
  @Value("${app.rate-limit.room-create.burst-max:5}")
  private int createBurstMax;

  @Value("${app.rate-limit.room-create.burst-window-seconds:600}")
  private long createBurstWindowSeconds;

  /** Daily guard: max rooms one user may create per rolling 24h. */
  @Value("${app.rate-limit.room-create.daily-max:10}")
  private int createDailyMax;

  @Value("${app.rate-limit.room-create.daily-window-seconds:86400}")
  private long createDailyWindowSeconds;

  @PostMapping
  public ResponseEntity<RoomResponse> createRoom(
      @AuthenticationPrincipal User user, @Valid @RequestBody CreateRoomRequest request) {
    String message = "Слишком много созданных комнат. Попробуйте позже.";
    if (createBurstMax > 0) {
      rateLimiter.check(
          "room-create-burst:" + user.getId(), createBurstMax, createBurstWindowSeconds, message);
    }
    if (createDailyMax > 0) {
      rateLimiter.check(
          "room-create-daily:" + user.getId(), createDailyMax, createDailyWindowSeconds, message);
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(roomService.createRoom(user, request));
  }

  @GetMapping
  public ResponseEntity<PagedResponse<RoomSummaryDto>> getRooms(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) RoomStatus status,
      @RequestParam(required = false) RoomType roomType,
      @RequestParam(required = false) Long categoryId,
      @RequestParam(required = false) Long serviceId,
      @RequestParam(required = false) java.math.BigDecimal priceMin,
      @RequestParam(required = false) java.math.BigDecimal priceMax,
      @RequestParam(required = false) Integer minFreeSeats,
      @RequestParam(required = false) AccessType accessType,
      @RequestParam(required = false) String region,
      @RequestParam(required = false) Boolean verifiedOwnerOnly,
      @RequestParam(required = false) String sortBy,
      @RequestParam(required = false) String sortDir) {
    RoomFilter filter =
        RoomFilter.builder()
            .status(status)
            .roomType(roomType)
            .categoryId(categoryId)
            .serviceId(serviceId)
            .priceMin(priceMin)
            .priceMax(priceMax)
            .minFreeSeats(minFreeSeats)
            .accessType(accessType)
            .region(region)
            .verifiedOwnerOnly(verifiedOwnerOnly)
            .build();
    return ResponseEntity.ok(roomService.getRooms(page, size, filter, sortBy, sortDir));
  }

  @GetMapping("/me")
  public ResponseEntity<PagedResponse<RoomSummaryDto>> getMyRooms(
      @AuthenticationPrincipal User user,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ResponseEntity.ok(roomService.getMyRooms(user, page, size));
  }

  @GetMapping("/{id}")
  public ResponseEntity<RoomResponse> getRoom(@PathVariable Long id) {
    return ResponseEntity.ok(roomService.getRoom(id));
  }

  /**
   * Returns (or lazily mints) the copy-pasteable invite link for the room owner. Owner-only — the
   * service layer rejects non-owners with a 403.
   */
  @GetMapping("/{id}/invite-link")
  public ResponseEntity<RoomInviteLinkDto> getInviteLink(
      @PathVariable Long id, @AuthenticationPrincipal User user) {
    return ResponseEntity.ok(roomService.getOrCreateInviteLink(id, user));
  }

  @PatchMapping("/{id}")
  public ResponseEntity<RoomResponse> updateRoom(
      @PathVariable Long id,
      @AuthenticationPrincipal User user,
      @Valid @RequestBody UpdateRoomRequest request) {
    return ResponseEntity.ok(roomService.updateRoom(id, user, request));
  }

  @PostMapping("/{id}/ready-for-verification")
  public ResponseEntity<RoomResponse> markReadyForVerification(
      @PathVariable Long id, @AuthenticationPrincipal User user) {
    return ResponseEntity.ok(roomService.markReadyForVerification(id, user));
  }

  @PostMapping("/{id}/cancel")
  public ResponseEntity<RoomResponse> cancelRoom(
      @PathVariable Long id, @AuthenticationPrincipal User user) {
    return ResponseEntity.ok(roomService.cancelRoom(id, user));
  }

  @PostMapping("/{id}/complete")
  public ResponseEntity<RoomResponse> completeRoom(
      @PathVariable Long id, @AuthenticationPrincipal User user) {
    return ResponseEntity.ok(roomService.completeRoom(id, user));
  }
}
