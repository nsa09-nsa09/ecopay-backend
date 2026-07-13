package kz.hrms.splitupauth.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kz.hrms.splitupauth.entity.PriceExtractorType;
import lombok.Data;

/**
 * Admin dry-run body: try an extraction recipe against a live URL without persisting anything.
 * Powers the "Test URL" button in the Upsert Provider modal so the admin sees the parsed price
 * before saving the row.
 */
@Data
public class TestPriceExtractionRequest {

  @NotBlank
  @Size(max = 2000)
  private String url;

  @NotNull private PriceExtractorType extractorType;

  /** Extractor-specific knobs; same shape as {@code price_watch_provider.extractor_config}. */
  private JsonNode extractorConfig;

  private Boolean requiresJs;

  @Size(max = 10)
  private String expectedCurrency;

  @Size(max = 20)
  private String locale;
}
