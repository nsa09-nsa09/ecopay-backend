package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kz.hrms.splitupauth.entity.ProviderType;
import kz.hrms.splitupauth.entity.ServiceAccessType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateServiceRequest {

  @NotNull private Long categoryId;

  @NotBlank
  @Size(max = 120)
  private String name;

  @Size(max = 120)
  private String slug;

  @NotNull private ProviderType providerType;

  /** Optional: defaults to PHONE for operators/ISPs and EMAIL for everything else. */
  private ServiceAccessType accessType;

  private Boolean isActive;
}
