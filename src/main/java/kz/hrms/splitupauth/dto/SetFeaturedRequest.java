package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SetFeaturedRequest {

  @NotNull private Boolean featured;

  @Min(1)
  @Max(6)
  private Integer homepagePosition;
}
