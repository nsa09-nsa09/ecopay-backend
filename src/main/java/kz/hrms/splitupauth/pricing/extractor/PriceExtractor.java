package kz.hrms.splitupauth.pricing.extractor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import kz.hrms.splitupauth.pricing.FetchedPage;
import kz.hrms.splitupauth.pricing.ParsedPrice;

/**
 * Strategy for lifting a single {@link ParsedPrice} out of a fetched HTML page.
 *
 * <p>Implementations should be stateless and return {@link Optional#empty()} rather than throwing
 * on parse failure — the caller records the outcome as {@code PARSE_FAILED} once every strategy
 * has been tried.
 */
public interface PriceExtractor {

  /** Recipe knobs read from {@code price_watch_provider.extractor_config}. */
  Optional<ParsedPrice> extract(FetchedPage page, JsonNode config);
}
