package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
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
public class ServiceDto {
  private Long id;
  private Long categoryId;
  private String categoryName;
  private String name;
  private String slug;
  private ProviderType providerType;

  /** What a joining member must provide to be let in: EMAIL, PHONE or BOTH. */
  private ServiceAccessType accessType;

  /** Cheapest per-member price across active tariffs; null when no active tariffs. */
  private BigDecimal minPricePerMember;

  /** Currency of the cheapest tariff; null when no active tariffs. */
  private String currency;

  /** Count of active tariffs. */
  private Integer tariffCount;

  /** Backend-served URL for the service logo, or null when none uploaded. */
  private String logoUrl;
}
