package kz.hrms.splitupauth.pricing.extractor;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kz.hrms.splitupauth.pricing.FetchedPage;
import kz.hrms.splitupauth.pricing.ParsedPrice;
import kz.hrms.splitupauth.pricing.PriceNumberParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * "Just get me a number" strategy that tries every other extractor in a sensible order and, if none
 * succeed, sweeps the raw body for a currency-symbol-adjacent number. The sweep is only meant to
 * bootstrap a provider — the admin is expected to promote the provider to a specific strategy once
 * they know which one lands cleanly.
 */
@Component
@RequiredArgsConstructor
public class AutoExtractor implements PriceExtractor {

  private final JsonLdExtractor jsonLd;
  private final MetaTagExtractor meta;
  private final CssSelectorExtractor css;
  private final RegexExtractor regex;

  /**
   * Currency-adjacent number: a symbol/code within ~10 chars of a numeric run. We reuse the central
   * parser for the final number normalisation.
   */
  private static final Pattern SWEEP =
      Pattern.compile(
          "(?<pre>(?:USD|EUR|RUB|KZT|TJS|UZS|KGS|GBP|CNY|₽|₸|\\$|€|£|тг|руб\\.?|сомони|сом)\\s*)"
              + "(?<num>\\d[\\d\\u00A0 .,]{0,15})",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern SWEEP_TRAILING =
      Pattern.compile(
          "(?<num>\\d[\\d\\u00A0 .,]{0,15})\\s*"
              + "(?<post>(?:USD|EUR|RUB|KZT|TJS|UZS|KGS|GBP|CNY|₽|₸|\\$|€|£|тг|руб\\.?|сомони|сом))",
          Pattern.CASE_INSENSITIVE);

  @Override
  public Optional<ParsedPrice> extract(FetchedPage page, JsonNode config) {
    // Order matters: JSON-LD > meta > CSS/regex (only if the admin filled config)
    // > body sweep. This mirrors "trust structured data first".
    Optional<ParsedPrice> hit = jsonLd.extract(page, config);
    if (hit.isPresent()) return hit;
    hit = meta.extract(page, config);
    if (hit.isPresent()) return hit;
    hit = css.extract(page, config);
    if (hit.isPresent()) return hit;
    hit = regex.extract(page, config);
    if (hit.isPresent()) return hit;
    return sweep(page.body(), page.expectedCurrency());
  }

  Optional<ParsedPrice> sweep(String body, String expectedCurrency) {
    if (body == null || body.isBlank()) return Optional.empty();
    for (Pattern p : List.of(SWEEP, SWEEP_TRAILING)) {
      Matcher m = p.matcher(body);
      while (m.find()) {
        String pre = safeGroup(m, "pre");
        String post = safeGroup(m, "post");
        String context = (pre == null ? "" : pre) + m.group("num") + (post == null ? "" : post);
        Optional<BigDecimal> num = PriceNumberParser.parseNumber(m.group("num"));
        if (num.isEmpty()) continue;
        // Skip absurd values that are almost always page metadata (years, IDs).
        BigDecimal value = num.get();
        if (value.signum() <= 0 || value.compareTo(new BigDecimal("1000000")) > 0) continue;
        String currency = PriceNumberParser.guessCurrency(context).orElse(expectedCurrency);
        return Optional.of(
            new ParsedPrice(value, currency == null ? null : currency.toUpperCase(), "auto:sweep"));
      }
    }
    return Optional.empty();
  }

  private static String safeGroup(Matcher m, String name) {
    try {
      return m.group(name);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }
}
