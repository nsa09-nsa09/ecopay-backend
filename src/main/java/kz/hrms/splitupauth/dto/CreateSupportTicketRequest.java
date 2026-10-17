package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateSupportTicketRequest {

  private Long roomId;
  private Long roomMemberId;

  @NotBlank(message = "Укажите тему обращения")
  @Size(min = 3, max = 200, message = "Тема должна быть от 3 до 200 символов")
  private String subject;

  @NotBlank(message = "Выберите категорию")
  @Size(max = 50, message = "Код категории должен быть не длиннее 50 символов")
  private String topic;

  @NotBlank(message = "Опишите вопрос")
  @Size(min = 10, max = 5000, message = "Сообщение должно быть от 10 до 5000 символов")
  private String message;
}
