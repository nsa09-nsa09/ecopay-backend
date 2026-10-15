package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CancelRoomRequest {

  /**
   * B10: cancellation reason the owner must provide — written into room_event_log and shared with
   * notified members. 5–500 characters.
   */
  @NotBlank(message = "Укажите причину отмены")
  @Size(min = 5, max = 500, message = "Причина должна быть от 5 до 500 символов")
  private String reason;
}
