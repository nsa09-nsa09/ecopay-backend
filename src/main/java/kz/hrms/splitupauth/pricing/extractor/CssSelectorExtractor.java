package kz.hrms.splitupauth.pricing.extractor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import kz.hrms.splitupauth.pricing.FetchedPage;
import kz.hrms.splitupauth.pricing.ParsedPrice;
import kz.hrms.splitupauth.pricing.PriceNumberParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

/**
 * Reads a price from the DOM element pointed to by a CSS selector stored in the extractor config.
 * Config shape (all optional except {@code selector}):
 *
 * <pre>{@code
 * { "selector": ".pricing-card__price", "attr": "data-price", "currency": "USD" }
 * }</pre>
 *
 * When {@code attr} is set, we read that HTML attribute instead of the element text — that is how
 * many pricing widgets carry the machine-readable number.
 */
@Component
public class CssSelectorExtractor implements PriceExtractor {

  private static final int MAX_SELECTOR_LENGTH = 300;
  private static final int MAX_MATCHED_NODES = 50;

  @Override
  public Optional<ParsedPrice> extract(FetchedPage page, JsonNode config) {
    if (config == null || !config.has("selector")) return Optional.empty();
    String selector = config.get("selector").asText();
    if (selector == null || selector.isBlank()) return Optional.empty();
    if (selector.length() > MAX_SELECTOR_LENGTH) return Optional.empty();

    Document doc = Jsoup.parse(page.body(), page.url() == null ? "" : page.url());
    Elements matches;
    try {
      matches = doc.select(selector);
    } catch (Exception ex) {
      // Malformed selector from the admin — return empty and let the outer
      // service record PARSE_FAILED with the error message.
      return Optional.empty();
    }
    if (matches.isEmpty() || matches.size() > MAX_MATCHED_NODES) return Optional.empty();
    Element el = matches.first();
    if (el == null) return Optional.empty();

    String attr = config.has("attr") ? config.get("attr").asText(null) : null;
    String raw = attr != null && !attr.isBlank() ? el.attr(attr) : el.text();
    if (raw == null || raw.isBlank()) return Optional.empty();

    Optional<java.math.BigDecimal> num = PriceNumberParser.parseNumber(raw);
    if (num.isEmpty()) return Optional.empty();

    String currencyHint = config.has("currency") ? config.get("currency").asText(null) : null;
    if (currencyHint == null) currencyHint = page.expectedCurrency();

    String detected = PriceNumberParser.guessCurrency(raw).orElse(currencyHint);
    return Optional.of(
        new ParsedPrice(
            num.get(), detected == null ? null : detected.toUpperCase(), "css:" + selector));
  }
}
