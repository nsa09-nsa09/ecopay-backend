package kz.hrms.splitupauth.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import kz.hrms.splitupauth.entity.PriceExtractorType;
import lombok.Data;

/** Admin POST body: register a new tracked subscription URL. */
@Data
public class CreatePriceWatchProviderRequest {

  @NotBlank
  @Size(max = 64)
  private String platformCode;

  @NotBlank
  @Size(max = 200)
  private String displayName;

  @NotBlank
  @Size(max = 200)
  private String planName;

  @NotBlank
  @Size(max = 2000)
  private String url;

  @Size(max = 20)
  private String locale;

  @Size(max = 10)
  private String expectedCurrency;

  @NotNull private PriceExtractorType extractorType;

  /** Extractor-specific knobs; see the extractor javadocs. Optional. */
  private JsonNode extractorConfig;

  private Boolean requiresJs;

  @Positive private Integer checkIntervalMinutes;

  private Boolean active;

  /** For MANUAL entries: initial known price. Ignored otherwise. */
  private BigDecimal initialPrice;

  @Size(max = 10)
  private String initialCurrency;
}
