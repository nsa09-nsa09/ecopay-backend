package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** A paid member's report that the room owner has not met their obligations. */
@Data
public class CreateRoomComplaintRequest {

  @NotBlank(message = "Укажите причину жалобы")
  @Size(max = 50, message = "Код причины должен быть не длиннее 50 символов")
  private String reasonCode;

  @NotBlank(message = "Опишите проблему")
  @Size(min = 10, max = 5000, message = "Описание должно быть от 10 до 5000 символов")
  private String description;
}
