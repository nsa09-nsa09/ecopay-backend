package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateReviewRequest {

  @NotNull(message = "Укажите, кому оставить отзыв")
  private Long recipientId;

  /**
   * B5: review must be anchored to a specific shared room — profile-level reviews are no longer
   * accepted since any user could rate any other user from the search page.
   */
  @NotNull(message = "Выберите комнату")
  private Long roomId;

  @NotNull(message = "Поставьте оценку")
  @Min(value = 1, message = "Оценка должна быть от 1 до 10")
  @Max(value = 10, message = "Оценка должна быть от 1 до 10")
  private Integer rating;

  @Size(max = 2000, message = "Отзыв должен быть не длиннее 2000 символов")
  private String text;
}
