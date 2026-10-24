package kz.hrms.splitupauth.controller;

import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.dto.RoomChatMessageDto;
import kz.hrms.splitupauth.dto.SendRoomChatMessageRequest;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.RoomChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Post-payment room chat. History + send go through REST (auth, validation, sanitizing); live
 * delivery is the STOMP topic {@code /topic/rooms/{id}/chat}. Participation (owner + PENDING/ACTIVE
 * members) is enforced in {@link RoomChatService}.
 */
@RestController
@RequestMapping("/api/v1/rooms/{roomId}/chat")
@RequiredArgsConstructor
public class RoomChatController {

  private final RoomChatService roomChatService;
  private final kz.hrms.splitupauth.service.RateLimiter rateLimiter;

  /** B15: 20 chat messages per minute per user. */
  @org.springframework.beans.factory.annotation.Value(
      "${app.rate-limit.room-chat.per-minute-max:20}")
  private int chatPerMinuteMax;

  @GetMapping("/messages")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<PagedResponse<RoomChatMessageDto>> history(
      @AuthenticationPrincipal User user,
      @PathVariable Long roomId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "30") int size) {
    return ResponseEntity.ok(roomChatService.history(user, roomId, page, size));
  }

  @PostMapping("/messages")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<RoomChatMessageDto> send(
      @AuthenticationPrincipal User user,
      @PathVariable Long roomId,
      @Valid @RequestBody SendRoomChatMessageRequest request) {
    if (chatPerMinuteMax > 0) {
      rateLimiter.check(
          "room-chat:" + user.getId(),
          chatPerMinuteMax,
          60,
          "Слишком много сообщений. Подождите минуту");
    }
    return ResponseEntity.ok(roomChatService.send(user, roomId, request.getBody()));
  }
}
