package kz.hrms.splitupauth.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import kz.hrms.splitupauth.entity.PriceExtractorType;
import lombok.Data;

/**
 * Admin PUT body. Every field is optional; only non-null values are applied so partial patches
 * work. Used by both "edit provider" and "enter manual price" flows.
 */
@Data
public class UpdatePriceWatchProviderRequest {

  @Size(max = 64)
  private String platformCode;

  @Size(max = 200)
  private String displayName;

  @Size(max = 200)
  private String planName;

  @Size(max = 2000)
  private String url;

  @Size(max = 20)
  private String locale;

  @Size(max = 10)
  private String expectedCurrency;

  private PriceExtractorType extractorType;
  private JsonNode extractorConfig;
  private Boolean requiresJs;

  @Positive private Integer checkIntervalMinutes;

  private Boolean active;

  /**
   * If provided, overrides {@code lastPrice}/{@code lastCurrency} directly. Intended for MANUAL
   * providers where the admin types the price they read on the vendor site.
   */
  private BigDecimal manualPrice;

  @Size(max = 10)
  private String manualCurrency;
}
