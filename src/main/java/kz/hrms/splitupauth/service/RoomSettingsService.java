package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.RoomSettingsDto;
import kz.hrms.splitupauth.entity.RoomSettings;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.RoomSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RoomSettingsService {

  static final long SETTINGS_ID = 1L;

  private final RoomSettingsRepository roomSettingsRepository;

  @Transactional(readOnly = true)
  public RoomSettingsDto getSettings() {
    return toDto(requireSettings());
  }

  @Transactional(readOnly = true)
  public int getMinimumRoomMembers() {
    return requireSettings().getMinimumRoomMembers();
  }

  @Transactional
  public RoomSettingsDto updateMinimumRoomMembers(Integer minimumRoomMembers) {
    if (minimumRoomMembers == null || minimumRoomMembers < 2) {
      throw new InvalidRequestException("minimumRoomMembers must be at least 2");
    }
    RoomSettings settings = requireSettings();
    settings.setMinimumRoomMembers(minimumRoomMembers);
    return toDto(roomSettingsRepository.save(settings));
  }

  private RoomSettings requireSettings() {
    return roomSettingsRepository
        .findById(SETTINGS_ID)
        .orElseThrow(() -> new ResourceNotFoundException("Room settings not found"));
  }

  private RoomSettingsDto toDto(RoomSettings settings) {
    return new RoomSettingsDto(settings.getMinimumRoomMembers());
  }
}
