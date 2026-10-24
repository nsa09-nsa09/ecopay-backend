package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import kz.hrms.splitupauth.entity.RoomSettings;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.RoomSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoomSettingsServiceTest {

  @Mock private RoomSettingsRepository roomSettingsRepository;
  @Mock private RoomRepository roomRepository;

  private RoomSettingsService roomSettingsService;

  @BeforeEach
  void setUp() {
    roomSettingsService = new RoomSettingsService(roomSettingsRepository);
  }

  @Test
  void getSettingsMapsDefaultMinimumRoomMembers() {
    RoomSettings settings = RoomSettings.builder().id(1L).minimumRoomMembers(5).build();
    when(roomSettingsRepository.findById(1L)).thenReturn(Optional.of(settings));

    assertEquals(5, roomSettingsService.getSettings().getMinimumRoomMembers());
    assertEquals(5, roomSettingsService.getMinimumRoomMembers());
  }

  @Test
  void adminCannotPersistMinimumBelowTwo() {
    assertThrows(
        InvalidRequestException.class, () -> roomSettingsService.updateMinimumRoomMembers(1));

    verify(roomSettingsRepository, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void updatingSettingOnlySavesSingletonSettingsRow() {
    RoomSettings settings = RoomSettings.builder().id(1L).minimumRoomMembers(5).build();
    when(roomSettingsRepository.findById(1L)).thenReturn(Optional.of(settings));
    when(roomSettingsRepository.save(settings)).thenReturn(settings);

    assertEquals(6, roomSettingsService.updateMinimumRoomMembers(6).getMinimumRoomMembers());
    verify(roomSettingsRepository).save(settings);
    verifyNoInteractions(roomRepository);
  }
}
