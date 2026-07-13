package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Dry-run result: what the same fetch + extract pipeline would have produced. No side effects on
 * providers, snapshots or the change feed — the admin can iterate on the recipe until it lights up
 * green, then hit Save.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestPriceExtractionResponse {

  /**
   * Coarse verdict. {@code SUCCESS} means we both fetched the page and pulled a number out;
   * anything else carries a diagnostic {@code message}. Kept as a top-level enum (not the internal
   * {@code PriceSnapshotOutcome}) so the frontend contract is stable even if we rename outcomes.
   */
  public enum Outcome {
    SUCCESS,
    PARSE_FAILED,
    FETCH_FAILED,
    BLOCKED
  }

  private Outcome outcome;
  private BigDecimal price;
  private String currency;
  private Integer httpStatus;

  /** Which extractor path lit up (e.g. "json_ld", "meta", "regex"), or {@code null}. */
  private String source;

  /** Short human-readable diagnostic — empty on SUCCESS. */
  private String message;
}
