package kz.hrms.splitupauth.pricing;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Locale-tolerant "text → BigDecimal + currency" helper used by every extractor.
 *
 * <p>Real-world subscription pages splash prices as {@code "1 990 ₸/mo"}, {@code "$9.99/month"},
 * {@code "€ 9,99"}, {@code "1.999,00 руб."} — each with a different thousand separator, decimal
 * separator and currency glyph position. Rather than encoding one rule per site, we normalise
 * whitespace, guess which separator is the decimal one (last of {@code . ,} that has ≤2 digits
 * after it), and match a small alphabet of common currency symbols/codes.
 */
public final class PriceNumberParser {

  private PriceNumberParser() {}

  private static final Map<String, String> SYMBOL_TO_ISO = buildSymbolTable();

  /** Common ISO codes we recognise inline. */
  private static final Pattern ISO_CODE =
      Pattern.compile(
          "\\b(USD|EUR|RUB|KZT|TJS|UZS|KGS|GBP|CNY|JPY|TRY|CAD|AUD|CHF|SEK|NOK|DKK|BRL|INR|PLN|AED)\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * Runs of "digits, spaces, dots, commas" plus optional trailing fractional. We anchor on at least
   * one digit so short "1,2" doesn't match year fragments etc.
   */
  private static final Pattern NUMBER = Pattern.compile("\\d[\\d\\u00A0 .,'’]*");

  public static Optional<BigDecimal> parseNumber(String raw) {
    if (raw == null) return Optional.empty();
    // Strip zero-width, NBSP-like and thin spaces first.
    String cleaned =
        raw.replace(' ', ' ')
            .replace(' ', ' ')
            .replace(' ', ' ')
            .replace('\'', ' ')
            .replace('’', ' ')
            .trim();
    Matcher m = NUMBER.matcher(cleaned);
    if (!m.find()) return Optional.empty();
    String hit = m.group().trim();
    return normaliseNumber(hit);
  }

  static Optional<BigDecimal> normaliseNumber(String text) {
    String s = text.replaceAll("[\\s]", "");
    if (s.isEmpty()) return Optional.empty();

    int lastComma = s.lastIndexOf(',');
    int lastDot = s.lastIndexOf('.');

    String integer;
    String fraction;
    if (lastComma < 0 && lastDot < 0) {
      integer = s;
      fraction = "";
    } else {
      int decIdx = Math.max(lastComma, lastDot);
      char decCh = s.charAt(decIdx);
      String tail = s.substring(decIdx + 1);
      boolean tailIsFraction =
          !tail.isEmpty() && tail.length() <= 2 && tail.chars().allMatch(Character::isDigit);
      // Also treat "12,50" (2-digit tail) or "12.5" (1-digit) as fraction.
      // If tail is 3 digits AND the other separator is present, this is a thousands group.
      if (!tailIsFraction) {
        // 1,234 — the comma/dot is a thousands separator; no fraction.
        integer = s.replace(",", "").replace(".", "");
        fraction = "";
      } else {
        String head = s.substring(0, decIdx);
        char otherSep = decCh == ',' ? '.' : ',';
        head = head.replace(String.valueOf(otherSep), "").replace(String.valueOf(decCh), "");
        integer = head;
        fraction = tail;
      }
    }
    if (integer.isEmpty() && fraction.isEmpty()) return Optional.empty();
    String norm = (integer.isEmpty() ? "0" : integer) + (fraction.isEmpty() ? "" : "." + fraction);
    try {
      return Optional.of(new BigDecimal(norm));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  public static Optional<String> guessCurrency(String context) {
    if (context == null) return Optional.empty();
    Matcher iso = ISO_CODE.matcher(context);
    if (iso.find()) {
      return Optional.of(iso.group(1).toUpperCase(Locale.ROOT));
    }
    for (Map.Entry<String, String> e : SYMBOL_TO_ISO.entrySet()) {
      if (context.contains(e.getKey())) {
        return Optional.of(e.getValue());
      }
    }
    return Optional.empty();
  }

  /** Best-effort combined parse: attempt to lift a price + currency from a free-form string. */
  public static Optional<ParsedPrice> parse(String context, String hintCurrency, String source) {
    Optional<BigDecimal> num = parseNumber(context);
    if (num.isEmpty()) return Optional.empty();
    String currency =
        guessCurrency(context)
            .orElse(hintCurrency == null ? null : hintCurrency.toUpperCase(Locale.ROOT));
    return Optional.of(new ParsedPrice(num.get(), currency, source));
  }

  private static Map<String, String> buildSymbolTable() {
    // Ordered longest-first so "руб." wins over "р.".
    Map<String, String> m = new LinkedHashMap<>();
    m.put("руб.", "RUB");
    m.put("руб", "RUB");
    m.put("₽", "RUB");
    m.put("₸", "KZT");
    m.put("тг", "KZT");
    m.put("сом", "KGS");
    m.put("сомони", "TJS");
    m.put("сўм", "UZS");
    m.put("$", "USD");
    m.put("€", "EUR");
    m.put("£", "GBP");
    m.put("¥", "JPY");
    m.put("₹", "INR");
    m.put("₺", "TRY");
    m.put("元", "CNY");
    m.put("zł", "PLN");
    return m;
  }
}
