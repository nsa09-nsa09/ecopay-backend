package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kz.hrms.splitupauth.entity.FeedbackType;
import lombok.Data;

@Data
public class CreateFeedbackRequest {

  @NotNull(message = "Выберите тип обратной связи")
  private FeedbackType type;

  @Size(max = 150, message = "Тема должна быть не длиннее 150 символов")
  private String subject;

  @NotBlank(message = "Введите сообщение")
  @Size(min = 5, max = 4000, message = "Сообщение должно быть от 5 до 4000 символов")
  private String message;
}
