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

  private static final int MAX_PATTERN_LENGTH = 500;
  private static final int MAX_INPUT_LENGTH = 500_000;
  private static final Pattern NESTED_QUANTIFIER = Pattern.compile("\\([^)]*[+*][^)]*\\)[+*?]");

  @Override
  public Optional<ParsedPrice> extract(FetchedPage page, JsonNode config) {
    if (config == null || !config.has("pattern")) return Optional.empty();
    String pattern = config.get("pattern").asText();
    if (pattern == null || pattern.isBlank()) return Optional.empty();
    if (pattern.length() > MAX_PATTERN_LENGTH || NESTED_QUANTIFIER.matcher(pattern).find()) {
      return Optional.empty();
    }

    Pattern p;
    try {
      p = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    } catch (Exception ex) {
      return Optional.empty();
    }
    String input = page.body() == null ? "" : page.body();
    if (input.length() > MAX_INPUT_LENGTH) {
      input = input.substring(0, MAX_INPUT_LENGTH);
    }
    Matcher m = p.matcher(input);
    if (!m.find()) return Optional.empty();
    int group = config.has("group") ? config.get("group").asInt(1) : 1;
    if (group < 0 || group > m.groupCount()) return Optional.empty();
    String hit = group == 0 ? m.group(0) : m.group(group);

    Optional<java.math.BigDecimal> num = PriceNumberParser.parseNumber(hit);
    if (num.isEmpty()) return Optional.empty();

    String currencyHint = config.has("currency") ? config.get("currency").asText(null) : null;
    if (currencyHint == null) currencyHint = page.expectedCurrency();
    String detected = PriceNumberParser.guessCurrency(hit).orElse(currencyHint);
    return Optional.of(
        new ParsedPrice(num.get(), detected == null ? null : detected.toUpperCase(), "regex"));
  }
}
