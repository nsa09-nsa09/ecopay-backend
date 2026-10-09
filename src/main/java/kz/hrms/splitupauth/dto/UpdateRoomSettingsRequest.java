package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateRoomSettingsRequest {
  @NotNull
  @Min(2)
  private Integer minimumRoomMembers;
}
