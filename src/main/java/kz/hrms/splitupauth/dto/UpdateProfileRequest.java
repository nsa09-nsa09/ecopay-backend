package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateProfileRequest {
  @NotBlank(message = "Display name is required")
  private String displayName;

  /**
   * Optional new URL handle. When {@code null} or blank the current slug is preserved. Validation
   * (pattern, reserved, uniqueness) happens in the service layer via {@code SlugService}.
   */
  private String slug;

  // Avatars are managed via POST/DELETE /api/v1/users/me/avatar (S3 upload),
  // not through this profile PATCH. The old public-URL field was removed.
}
