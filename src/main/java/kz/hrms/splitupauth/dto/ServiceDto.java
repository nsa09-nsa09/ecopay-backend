package kz.hrms.splitupauth.dto;

import kz.hrms.splitupauth.entity.ProviderType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

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
    /** Cheapest per-member price across active tariffs; null when no active tariffs. */
    private BigDecimal minPricePerMember;
    /** Currency of the cheapest tariff; null when no active tariffs. */
    private String currency;
    /** Count of active tariffs. */
    private Integer tariffCount;
}
