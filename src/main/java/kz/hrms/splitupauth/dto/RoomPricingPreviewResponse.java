package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RoomPricingPreviewResponse {
  private Integer maxMembers;
  private Integer existingMembersCount;
  private Integer marketplaceCapacity;
  private BigDecimal shareKzt;
  private BigDecimal commissionKzt;
  private BigDecimal payableTotalKzt;
  private BigDecimal potentialOwnerPayoutKzt;
  private BigDecimal potentialEcoPayCommissionKzt;
  private BigDecimal potentialMemberPaymentsTotalKzt;
  private BigDecimal originalTariffPrice;
  private String originalTariffCurrency;
  private BigDecimal fxRateSnapshot;
  private String settlementCurrency;
}
