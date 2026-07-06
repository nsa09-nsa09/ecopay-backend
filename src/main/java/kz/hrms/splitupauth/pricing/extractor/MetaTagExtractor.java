package kz.hrms.splitupauth.pricing.extractor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import kz.hrms.splitupauth.pricing.FetchedPage;
import kz.hrms.splitupauth.pricing.ParsedPrice;
import kz.hrms.splitupauth.pricing.PriceNumberParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * OpenGraph / Twitter / schema.org meta-tag prices. Handles both {@code <meta property="og:...">}
 * and {@code <meta name="...">} variants; also {@code <meta itemprop="price">}.
 */
@Component
public class MetaTagExtractor implements PriceExtractor {

  private static final String[] PRICE_ATTRS = {
      "meta[itemprop=price]",
      "meta[property=product:price:amount]",
      "meta[property=og:price:amount]",
      "meta[property=og:product:price:amount]",
      "meta[name=twitter:data1]",
      "meta[name=price]",
  };

  private static final String[] CURRENCY_ATTRS = {
      "meta[itemprop=priceCurrency]",
      "meta[property=product:price:currency]",
      "meta[property=og:price:currency]",
      "meta[property=og:product:price:currency]",
      "meta[name=twitter:data2]",
      "meta[name=currency]",
  };

  @Override
  public Optional<ParsedPrice> extract(FetchedPage page, JsonNode config) {
    Document doc = Jsoup.parse(page.body(), page.url() == null ? "" : page.url());
    String priceRaw = firstContent(doc, PRICE_ATTRS);
    if (priceRaw == null) return Optional.empty();
    Optional<java.math.BigDecimal> num = PriceNumberParser.parseNumber(priceRaw);
    if (num.isEmpty()) return Optional.empty();
    String currency = firstContent(doc, CURRENCY_ATTRS);
    if (currency == null && page.expectedCurrency() != null) currency = page.expectedCurrency();
    return Optional.of(new ParsedPrice(num.get(), currency == null ? null : currency.toUpperCase(),
        "meta"));
  }

  private static String firstContent(Document doc, String[] selectors) {
    for (String s : selectors) {
      Element el = doc.selectFirst(s);
      if (el != null) {
        String v = el.attr("content");
        if (v != null && !v.isBlank()) return v.trim();
      }
    }
    return null;
  }
}
