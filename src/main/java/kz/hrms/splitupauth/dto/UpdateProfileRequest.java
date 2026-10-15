package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateProfileRequest {
  @NotBlank(message = "Укажите имя для отображения")
  @Size(min = 2, max = 50, message = "Имя должно быть от 2 до 50 символов")
  private String displayName;

  /**
   * Optional new URL handle. When {@code null} or blank the current slug is preserved. Validation
   * (pattern, reserved, uniqueness) happens in the service layer via {@code SlugService}.
   */
  private String slug;

  // Avatars are managed via POST/DELETE /api/v1/users/me/avatar (S3 upload),
  // not through this profile PATCH. The old public-URL field was removed.
}
