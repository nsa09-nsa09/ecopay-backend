package kz.hrms.splitupauth.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Optional;
import kz.hrms.splitupauth.pricing.extractor.AutoExtractor;
import kz.hrms.splitupauth.pricing.extractor.CssSelectorExtractor;
import kz.hrms.splitupauth.pricing.extractor.JsonLdExtractor;
import kz.hrms.splitupauth.pricing.extractor.MetaTagExtractor;
import kz.hrms.splitupauth.pricing.extractor.RegexExtractor;
import org.junit.jupiter.api.Test;

/**
 * Exercises every extractor against a local HTML fixture. No network involved — {@link FetchedPage}
 * is constructed directly.
 */
class PriceExtractorsTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final JsonLdExtractor jsonLd = new JsonLdExtractor(mapper);
  private final MetaTagExtractor meta = new MetaTagExtractor();
  private final CssSelectorExtractor css = new CssSelectorExtractor();
  private final RegexExtractor regex = new RegexExtractor();
  private final AutoExtractor auto = new AutoExtractor(jsonLd, meta, css, regex);

  private FetchedPage load(String fixture, String currency, String locale) throws Exception {
    var stream = getClass().getResourceAsStream("/pricing/" + fixture);
    assertNotNull(stream, "fixture missing: " + fixture);
    String body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    return new FetchedPage(
        "https://example.test/", 200, body, Collections.emptyMap(), currency, locale);
  }

  @Test
  void jsonLdExtractor_readsOfferPrice() throws Exception {
    FetchedPage page = load("json-ld.html", null, "en");
    Optional<ParsedPrice> parsed = jsonLd.extract(page, null);
    assertTrue(parsed.isPresent(), "json-ld extractor should find schema.org offer");
    assertEquals(0, new BigDecimal("9.99").compareTo(parsed.get().price()));
    assertEquals("USD", parsed.get().currency());
  }

  @Test
  void metaExtractor_readsOgPriceAmount() throws Exception {
    FetchedPage page = load("meta.html", null, "ru");
    Optional<ParsedPrice> parsed = meta.extract(page, null);
    assertTrue(parsed.isPresent());
    assertEquals(0, new BigDecimal("1990.00").compareTo(parsed.get().price()));
    assertEquals("KZT", parsed.get().currency());
  }

  @Test
  void cssExtractor_readsPriceFromSelector() throws Exception {
    FetchedPage page = load("css.html", "KZT", "ru");
    JsonNode config = mapper.readTree("{\"selector\":\".pricing-card__price\"}");
    Optional<ParsedPrice> parsed = css.extract(page, config);
    assertTrue(parsed.isPresent(), "css extractor should find the selected element");
    assertEquals(0, new BigDecimal("1990").compareTo(parsed.get().price()));
    assertEquals("KZT", parsed.get().currency(), "currency guessed from ₸ symbol");
  }

  @Test
  void autoExtractor_prefersJsonLd() throws Exception {
    FetchedPage page = load("json-ld.html", null, "en");
    Optional<ParsedPrice> parsed = auto.extract(page, null);
    assertTrue(parsed.isPresent());
    assertEquals(0, new BigDecimal("9.99").compareTo(parsed.get().price()));
    assertEquals("json-ld:price", parsed.get().source());
  }

  @Test
  void autoExtractor_fallsBackToSweep() {
    String body =
        "<html><body><h2>Тариф Premium</h2>" + "<div>Только $12.99 в месяц</div></body></html>";
    FetchedPage page =
        new FetchedPage("https://example.test/", 200, body, Collections.emptyMap(), null, "en");
    Optional<ParsedPrice> parsed = auto.extract(page, null);
    assertTrue(parsed.isPresent(), "sweep should catch \"$12.99\"");
    assertEquals(0, new BigDecimal("12.99").compareTo(parsed.get().price()));
    assertEquals("USD", parsed.get().currency());
  }

  @Test
  void numberParser_handlesLocaleQuirks() {
    // Space thousands separator + comma decimal (French / Russian).
    assertEquals(
        0,
        new BigDecimal("1990.00")
            .compareTo(PriceNumberParser.parseNumber("1 990,00 ₸").orElseThrow()));
    // Dot thousands separator + comma decimal (German).
    assertEquals(
        0,
        new BigDecimal("1999.00")
            .compareTo(PriceNumberParser.parseNumber("1.999,00 EUR").orElseThrow()));
    // Anglo default.
    assertEquals(
        0,
        new BigDecimal("1,299.50".replace(",", ""))
            .compareTo(PriceNumberParser.parseNumber("$1,299.50").orElseThrow()));
    // No fraction, plain integer with NBSP thousands.
    assertEquals(
        0,
        new BigDecimal("2500").compareTo(PriceNumberParser.parseNumber("2 500 сом").orElseThrow()));
  }
}
