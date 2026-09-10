package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RoomPricingPreviewRequest {
  @NotNull private Long tariffPlanId;

  @NotNull
  @Min(1)
  @Max(2)
  private Integer existingMembersCount;
}
