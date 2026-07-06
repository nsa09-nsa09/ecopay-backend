package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Admin change-feed row. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceChangeDto {
  /** String-encoded — see {@code PriceWatchProviderDto#id}. */
  private String id;

  private String providerId;
  private String providerName;
  private String planName;
  private BigDecimal oldPrice;
  private BigDecimal newPrice;
  private String currency;
  private LocalDateTime changedAt;
  private String snapshotId;
  private Boolean acknowledged;
}
