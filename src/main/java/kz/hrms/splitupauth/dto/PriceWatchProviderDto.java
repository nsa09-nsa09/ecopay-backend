package kz.hrms.splitupauth.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import kz.hrms.splitupauth.entity.PriceExtractorType;
import kz.hrms.splitupauth.entity.PriceWatchStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Admin-facing shape of one Price Watch provider. Includes the live health fields
 * ({@code status}, {@code lastPrice}, {@code lastChangedAt}) so the admin table can render one
 * row per provider without joining snapshots.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceWatchProviderDto {
  /**
   * Serialised as a JSON string, not a number: CockroachDB's BIGSERIAL emits ids past 2^53 which
   * JavaScript's number type silently rounds on {@code JSON.parse}. See {@code PriceWatchMapper}.
   */
  private String id;

  private String platformCode;
  private String displayName;
  private String planName;
  private String url;
  private String locale;
  private String expectedCurrency;
  private PriceExtractorType extractorType;
  private JsonNode extractorConfig;
  private Boolean requiresJs;
  private Integer checkIntervalMinutes;
  private Boolean active;
  private PriceWatchStatus status;
  private Integer consecutiveFailures;
  private LocalDateTime lastCheckedAt;
  private LocalDateTime lastSuccessAt;
  private LocalDateTime nextCheckAt;
  private BigDecimal lastPrice;
  private String lastCurrency;

  /** Timestamp of the most recent {@code price_change} row, or {@code null} if none. */
  private LocalDateTime lastChangedAt;

  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
