package kz.hrms.splitupauth.pricing.extractor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kz.hrms.splitupauth.pricing.FetchedPage;
import kz.hrms.splitupauth.pricing.ParsedPrice;
import kz.hrms.splitupauth.pricing.PriceNumberParser;
import org.springframework.stereotype.Component;

/**
 * Last-resort recipe: an admin-provided regex with one capturing group that isolates the number.
 * Config shape:
 *
 * <pre>{@code
 * { "pattern": "price\\\":\\s*\\\"(\\d+[.,]\\d+)", "currency": "USD" }
 * }</pre>
 *
 * The pattern is applied to the whole page body, so a very loose regex will happily match the wrong
 * thing — that's on the admin. We keep the first capturing group.
 */
@Component
public class RegexExtractor implements PriceExtractor {

  @Override
  public Optional<ParsedPrice> extract(FetchedPage page, JsonNode config) {
    if (config == null || !config.has("pattern")) return Optional.empty();
    String pattern = config.get("pattern").asText();
    if (pattern == null || pattern.isBlank()) return Optional.empty();

    Pattern p;
    try {
      p = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    } catch (Exception ex) {
      return Optional.empty();
    }
    Matcher m = p.matcher(page.body());
    if (!m.find()) return Optional.empty();
    String hit = m.groupCount() >= 1 ? m.group(1) : m.group(0);

    Optional<java.math.BigDecimal> num = PriceNumberParser.parseNumber(hit);
    if (num.isEmpty()) return Optional.empty();

    String currencyHint = config.has("currency") ? config.get("currency").asText(null) : null;
    if (currencyHint == null) currencyHint = page.expectedCurrency();
    String detected = PriceNumberParser.guessCurrency(hit).orElse(currencyHint);
    return Optional.of(
        new ParsedPrice(num.get(), detected == null ? null : detected.toUpperCase(), "regex"));
  }
}
