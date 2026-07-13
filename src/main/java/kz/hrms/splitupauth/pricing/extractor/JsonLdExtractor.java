package kz.hrms.splitupauth.pricing.extractor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Optional;
import kz.hrms.splitupauth.pricing.FetchedPage;
import kz.hrms.splitupauth.pricing.ParsedPrice;
import kz.hrms.splitupauth.pricing.PriceNumberParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Reads schema.org JSON-LD blocks — the most robust price signal when a site publishes it, because
 * it is what Google indexes. Walks every {@code <script type="application/ld+json">} block and
 * looks for {@code offers.price} (or nested {@code offers[].price}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JsonLdExtractor implements PriceExtractor {

  private final ObjectMapper objectMapper;

  @Override
  public Optional<ParsedPrice> extract(FetchedPage page, JsonNode config) {
    Document doc = Jsoup.parse(page.body(), page.url() == null ? "" : page.url());
    for (Element script : doc.select("script[type=application/ld+json]")) {
      String raw = script.data();
      if (raw == null || raw.isBlank()) continue;
      Optional<ParsedPrice> hit = fromBlock(raw, page.expectedCurrency());
      if (hit.isPresent()) return hit;
    }
    return Optional.empty();
  }

  private Optional<ParsedPrice> fromBlock(String raw, String hintCurrency) {
    try {
      JsonNode root = objectMapper.readTree(raw);
      return walk(root, hintCurrency);
    } catch (Exception ex) {
      // JSON-LD blocks in the wild are frequently non-strict (trailing commas, JS
      // comments). Failing on one block is fine — the AutoExtractor will fall
      // through to meta/CSS/sweep.
      log.debug("json-ld block parse failed: {}", ex.getMessage());
      return Optional.empty();
    }
  }

  private Optional<ParsedPrice> walk(JsonNode node, String hintCurrency) {
    if (node == null || node.isMissingNode() || node.isNull()) return Optional.empty();
    if (node.isArray()) {
      for (JsonNode c : node) {
        Optional<ParsedPrice> hit = walk(c, hintCurrency);
        if (hit.isPresent()) return hit;
      }
      return Optional.empty();
    }
    if (!node.isObject()) return Optional.empty();

    // Direct offer shape: {"price": "9.99", "priceCurrency": "USD"} or a numeric.
    JsonNode priceNode = node.get("price");
    if (priceNode != null && !priceNode.isNull()) {
      Optional<BigDecimal> value = readNumber(priceNode);
      if (value.isPresent()) {
        JsonNode currency = node.get("priceCurrency");
        String cur = currency != null && !currency.isNull() ? currency.asText() : hintCurrency;
        if (cur != null) cur = cur.toUpperCase();
        return Optional.of(new ParsedPrice(value.get(), cur, "json-ld:price"));
      }
    }
    JsonNode priceSpec = node.get("priceSpecification");
    if (priceSpec != null) {
      Optional<ParsedPrice> hit = walk(priceSpec, hintCurrency);
      if (hit.isPresent()) return hit;
    }
    JsonNode offers = node.get("offers");
    if (offers != null) {
      Optional<ParsedPrice> hit = walk(offers, hintCurrency);
      if (hit.isPresent()) return hit;
    }
    // Recurse into all remaining fields — many pages nest an offer several
    // levels deep inside a Graph.
    var fields = node.fields();
    while (fields.hasNext()) {
      var e = fields.next();
      if ("price".equals(e.getKey())
          || "priceCurrency".equals(e.getKey())
          || "priceSpecification".equals(e.getKey())
          || "offers".equals(e.getKey())) {
        continue;
      }
      Optional<ParsedPrice> hit = walk(e.getValue(), hintCurrency);
      if (hit.isPresent()) return hit;
    }
    return Optional.empty();
  }

  private static Optional<BigDecimal> readNumber(JsonNode node) {
    if (node.isNumber()) {
      return Optional.of(node.decimalValue());
    }
    if (node.isTextual()) {
      return PriceNumberParser.parseNumber(node.asText());
    }
    return Optional.empty();
  }
}
