package kz.hrms.splitupauth.controller;

import kz.hrms.splitupauth.dto.RoomSettingsDto;
import kz.hrms.splitupauth.service.RoomSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/site/room-settings")
@RequiredArgsConstructor
public class RoomSettingsController {

  private final RoomSettingsService roomSettingsService;

  @GetMapping
  public ResponseEntity<RoomSettingsDto> getSettings() {
    return ResponseEntity.ok(roomSettingsService.getSettings());
  }
}
