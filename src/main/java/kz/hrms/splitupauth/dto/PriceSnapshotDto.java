package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import kz.hrms.splitupauth.entity.PriceSnapshotOutcome;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row of a provider's price history. {@code rawExcerpt} is intentionally omitted from the
 * default list DTO — call the detail endpoint if a diagnostic dump is needed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceSnapshotDto {
  /** String-encoded — see {@code PriceWatchProviderDto#id}. */
  private String id;

  private String providerId;
  private BigDecimal price;
  private String currency;
  private LocalDateTime capturedAt;
  private PriceSnapshotOutcome outcome;
  private Integer httpStatus;
  private String errorMessage;
}
