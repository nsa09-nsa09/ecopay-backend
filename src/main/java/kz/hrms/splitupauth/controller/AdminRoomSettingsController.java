package kz.hrms.splitupauth.controller;

import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.RoomSettingsDto;
import kz.hrms.splitupauth.dto.UpdateRoomSettingsRequest;
import kz.hrms.splitupauth.service.RoomSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/room-settings")
@RequiredArgsConstructor
public class AdminRoomSettingsController {

  private final RoomSettingsService roomSettingsService;

  @GetMapping
  public ResponseEntity<RoomSettingsDto> getSettings() {
    return ResponseEntity.ok(roomSettingsService.getSettings());
  }

  @PatchMapping
  public ResponseEntity<RoomSettingsDto> updateSettings(
      @Valid @RequestBody UpdateRoomSettingsRequest request) {
    return ResponseEntity.ok(
        roomSettingsService.updateMinimumRoomMembers(request.getMinimumRoomMembers()));
  }
}
