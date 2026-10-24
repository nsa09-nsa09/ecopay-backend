package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateDisputeStatementRequest {

  @NotBlank(message = "Текст обязателен")
  @Size(min = 10, max = 2000, message = "Текст должен быть от 10 до 2000 символов")
  private String text;
}
