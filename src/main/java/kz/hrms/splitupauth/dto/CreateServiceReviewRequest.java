package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateServiceReviewRequest {

  @NotNull(message = "Поставьте оценку")
  @Min(value = 1, message = "Оценка должна быть от 1 до 5")
  @Max(value = 5, message = "Оценка должна быть от 1 до 5")
  private Integer rating;

  @NotBlank(message = "Опишите впечатление")
  @Size(min = 3, max = 2000, message = "Отзыв должен быть от 3 до 2000 символов")
  private String text;
}
