package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateSupportMessageRequest {

  @NotBlank(message = "Введите сообщение")
  @Size(min = 1, max = 5000, message = "Сообщение должно быть не длиннее 5000 символов")
  private String message;
}
