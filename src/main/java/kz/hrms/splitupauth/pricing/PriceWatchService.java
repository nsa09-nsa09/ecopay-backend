package kz.hrms.splitupauth.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import kz.hrms.splitupauth.dto.TestPriceExtractionRequest;
import kz.hrms.splitupauth.dto.TestPriceExtractionResponse;
import kz.hrms.splitupauth.entity.PriceChange;
import kz.hrms.splitupauth.entity.PriceExtractorType;
import kz.hrms.splitupauth.entity.PriceSnapshot;
import kz.hrms.splitupauth.entity.PriceSnapshotOutcome;
import kz.hrms.splitupauth.entity.PriceWatchProvider;
import kz.hrms.splitupauth.entity.PriceWatchStatus;
import kz.hrms.splitupauth.exception.TooManyRequestsException;
import kz.hrms.splitupauth.pricing.extractor.AutoExtractor;
import kz.hrms.splitupauth.pricing.extractor.CssSelectorExtractor;
import kz.hrms.splitupauth.pricing.extractor.JsonLdExtractor;
import kz.hrms.splitupauth.pricing.extractor.MetaTagExtractor;
import kz.hrms.splitupauth.pricing.extractor.PriceExtractor;
import kz.hrms.splitupauth.pricing.extractor.RegexExtractor;
import kz.hrms.splitupauth.repository.PriceChangeRepository;
import kz.hrms.splitupauth.repository.PriceSnapshotRepository;
import kz.hrms.splitupauth.repository.PriceWatchProviderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PriceWatchService {

  private final PriceWatchProviderRepository providerRepository;
  private final PriceSnapshotRepository snapshotRepository;
  private final PriceChangeRepository changeRepository;
  private final PageFetcher pageFetcher;
  private final JsonLdExtractor jsonLdExtractor;
  private final MetaTagExtractor metaExtractor;
  private final CssSelectorExtractor cssExtractor;
  private final RegexExtractor regexExtractor;
  private final AutoExtractor autoExtractor;

  @Value("${app.pricing.failure-threshold:5}")
  private int failureThreshold;

  @Value("${app.pricing.default-interval-minutes:720}")
  private int defaultIntervalMinutes;

  @Value("${app.pricing.lease-minutes:10}")
  private int leaseMinutes;

  @Value("${app.pricing.store-sanitized-snippet:false}")
  private boolean storeSanitizedSnippet;

  @Value("${app.pricing.max-price:1000000}")
  private BigDecimal maxPrice = new BigDecimal("1000000");

  private final String leaseOwner = "price-watch-" + UUID.randomUUID();

  public PriceWatchProvider checkProvider(Long providerId) {
    LocalDateTime now = LocalDateTime.now();
    int claimed =
        providerRepository.claim(providerId, leaseOwner, now.plusMinutes(leaseMinutes), now);
    if (claimed == 0) {
      throw new TooManyRequestsException("Price provider is already being checked");
    }
    try {
      PriceWatchProvider provider =
          providerRepository
              .findById(providerId)
              .orElseThrow(() -> new IllegalArgumentException("provider not found: " + providerId));
      return check(provider);
    } finally {
      providerRepository.release(providerId, leaseOwner);
    }
  }

  public PriceWatchProvider check(PriceWatchProvider provider) {
    LocalDateTime now = LocalDateTime.now();
    provider.setLastCheckedAt(now);

    if (provider.getExtractorType() == PriceExtractorType.MANUAL) {
      provider.setStatus(PriceWatchStatus.PENDING);
      provider.setNextCheckAt(scheduleNext(provider, now));
      return providerRepository.save(provider);
    }

    FetchResult fetch = pageFetcher.fetch(provider, null, null);
    if (fetch.notModified()) {
      provider.setLastSuccessAt(now);
      provider.setConsecutiveFailures(0);
      provider.setStatus(PriceWatchStatus.OK);
      provider.setNextCheckAt(scheduleNext(provider, now));
      return providerRepository.save(provider);
    }

    if (fetch.outcome() != PriceSnapshotOutcome.SUCCESS) {
      recordSnapshot(
          provider,
          null,
          null,
          fetch.outcome(),
          fetch.httpStatus(),
          null,
          null,
          fetch.errorMessage());
      if (isBlockingOutcome(fetch.outcome())) {
        provider.setStatus(PriceWatchStatus.BLOCKED);
        provider.setNextCheckAt(scheduleNext(provider, now));
        return providerRepository.save(provider);
      }
      return markFailure(provider, now);
    }

    FetchedPage page = fetch.page();
    Optional<ParsedPrice> parsed = runExtractor(provider, page);
    String excerpt = evidenceSnippet(page.body(), provider.getUrl());
    String bodyHash = hash(page.body());

    if (parsed.isEmpty()) {
      recordSnapshot(
          provider,
          null,
          null,
          PriceSnapshotOutcome.PARSE_FAILED,
          page.status(),
          excerpt,
          bodyHash,
          "no price found by extractor " + provider.getExtractorType());
      return markFailure(provider, now);
    }

    ParsedPrice p = parsed.get();
    Optional<String> priceError = validateParsedPrice(provider, p);
    if (priceError.isPresent()) {
      recordSnapshot(
          provider,
          null,
          p.currency(),
          PriceSnapshotOutcome.CURRENCY_MISMATCH,
          page.status(),
          excerpt,
          bodyHash,
          priceError.get());
      provider.setStatus(PriceWatchStatus.BLOCKED);
      provider.setNextCheckAt(scheduleNext(provider, now));
      return providerRepository.save(provider);
    }

    PriceSnapshot snapshot =
        recordSnapshot(
            provider,
            p.price(),
            p.currency(),
            PriceSnapshotOutcome.SUCCESS,
            page.status(),
            excerpt,
            bodyHash,
            null);

    detectChange(provider, p, snapshot);

    provider.setLastPrice(p.price());
    provider.setLastCurrency(p.currency());
    provider.setLastSuccessAt(now);
    provider.setConsecutiveFailures(0);
    provider.setStatus(PriceWatchStatus.OK);
    provider.setNextCheckAt(scheduleNext(provider, now));
    return providerRepository.save(provider);
  }

  public TestPriceExtractionResponse testExtraction(TestPriceExtractionRequest req) {
    PriceWatchProvider probe = new PriceWatchProvider();
    probe.setUrl(req.getUrl());
    probe.setExtractorType(req.getExtractorType());
    probe.setExtractorConfig(
        req.getExtractorConfig() != null
            ? req.getExtractorConfig()
            : JsonNodeFactory.instance.objectNode());
    probe.setRequiresJs(Boolean.TRUE.equals(req.getRequiresJs()));
    probe.setExpectedCurrency(req.getExpectedCurrency());
    probe.setLocale(req.getLocale());

    if (req.getExtractorType() == PriceExtractorType.MANUAL) {
      return TestPriceExtractionResponse.builder()
          .outcome(TestPriceExtractionResponse.Outcome.PARSE_FAILED)
          .message("MANUAL extractor cannot be tested against a URL")
          .build();
    }

    FetchResult fetch = pageFetcher.fetch(probe, null, null);
    if (fetch.notModified()) {
      return TestPriceExtractionResponse.builder()
          .outcome(TestPriceExtractionResponse.Outcome.SUCCESS)
          .httpStatus(fetch.httpStatus())
          .message("304 Not Modified")
          .build();
    }
    if (fetch.outcome() != PriceSnapshotOutcome.SUCCESS) {
      return TestPriceExtractionResponse.builder()
          .outcome(toDryRunOutcome(fetch.outcome()))
          .httpStatus(fetch.httpStatus())
          .message(fetch.errorMessage())
          .build();
    }

    FetchedPage page = fetch.page();
    Optional<ParsedPrice> parsed = runExtractor(probe, page);
    if (parsed.isEmpty()) {
      return TestPriceExtractionResponse.builder()
          .outcome(TestPriceExtractionResponse.Outcome.PARSE_FAILED)
          .httpStatus(page.status())
          .message("no price found by extractor " + req.getExtractorType())
          .build();
    }
    ParsedPrice p = parsed.get();
    Optional<String> priceError = validateParsedPrice(probe, p);
    if (priceError.isPresent()) {
      return TestPriceExtractionResponse.builder()
          .outcome(TestPriceExtractionResponse.Outcome.CURRENCY_MISMATCH)
          .httpStatus(page.status())
          .message(priceError.get())
          .build();
    }
    return TestPriceExtractionResponse.builder()
        .outcome(TestPriceExtractionResponse.Outcome.SUCCESS)
        .price(p.price())
        .currency(p.currency())
        .httpStatus(page.status())
        .source(p.source())
        .build();
  }

  private Optional<ParsedPrice> runExtractor(PriceWatchProvider provider, FetchedPage page) {
    JsonNode config = provider.getExtractorConfig();
    PriceExtractor extractor =
        switch (provider.getExtractorType()) {
          case JSON_LD -> jsonLdExtractor;
          case META -> metaExtractor;
          case CSS -> cssExtractor;
          case REGEX -> regexExtractor;
          case AUTO -> autoExtractor;
          case MANUAL -> null;
        };
    if (extractor == null) return Optional.empty();
    try {
      return extractor.extract(page, config);
    } catch (Exception ex) {
      log.warn(
          "Extractor {} for provider {} threw: {}",
          provider.getExtractorType(),
          provider.getId(),
          ex.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  PriceWatchProvider markFailure(PriceWatchProvider provider, LocalDateTime now) {
    int fails = Optional.ofNullable(provider.getConsecutiveFailures()).orElse(0) + 1;
    provider.setConsecutiveFailures(fails);
    if (fails >= failureThreshold) {
      provider.setStatus(PriceWatchStatus.FAILING);
      provider.setActive(false);
      log.warn("Price provider {} disabled after {} consecutive failures", provider.getId(), fails);
    } else {
      provider.setStatus(PriceWatchStatus.STALE);
    }
    provider.setNextCheckAt(scheduleNext(provider, now));
    return providerRepository.save(provider);
  }

  private PriceSnapshot recordSnapshot(
      PriceWatchProvider provider,
      BigDecimal price,
      String currency,
      PriceSnapshotOutcome outcome,
      Integer httpStatus,
      String excerpt,
      String bodyHash,
      String errorMessage) {
    PriceSnapshot snap =
        PriceSnapshot.builder()
            .provider(provider)
            .price(price)
            .currency(normalizeCurrency(currency))
            .outcome(outcome)
            .httpStatus(httpStatus)
            .rawExcerpt(excerpt)
            .bodyHash(bodyHash)
            .errorMessage(normalizeMessage(errorMessage))
            .build();
    return snapshotRepository.save(snap);
  }

  private void detectChange(PriceWatchProvider provider, ParsedPrice p, PriceSnapshot snap) {
    BigDecimal old = provider.getLastPrice();
    if (old == null || old.compareTo(p.price()) != 0) {
      changeRepository.save(
          PriceChange.builder()
              .provider(provider)
              .oldPrice(old)
              .newPrice(p.price())
              .currency(p.currency())
              .snapshot(snap)
              .build());
    }
  }

  private LocalDateTime scheduleNext(PriceWatchProvider provider, LocalDateTime from) {
    int minutes =
        provider.getCheckIntervalMinutes() == null
            ? defaultIntervalMinutes
            : provider.getCheckIntervalMinutes();
    if (minutes < 15) minutes = 15;
    int jitter =
        (int) Math.round(minutes * 0.15 * (ThreadLocalRandom.current().nextDouble() * 2 - 1));
    return from.plusMinutes(Math.max(15, minutes + jitter));
  }

  private Optional<String> validateParsedPrice(PriceWatchProvider provider, ParsedPrice parsed) {
    if (parsed.price() == null
        || parsed.price().signum() <= 0
        || parsed.price().compareTo(maxPrice) > 0) {
      return Optional.of("Parsed price is outside the allowed range");
    }
    String currency = normalizeCurrency(parsed.currency());
    String expected = normalizeCurrency(provider.getExpectedCurrency());
    if (currency == null) return Optional.of("Parsed currency is missing");
    if (expected != null && !expected.equals(currency)) {
      return Optional.of("Parsed currency does not match expected currency");
    }
    return Optional.empty();
  }

  private static boolean isBlockingOutcome(PriceSnapshotOutcome outcome) {
    return outcome == PriceSnapshotOutcome.DNS_BLOCKED
        || outcome == PriceSnapshotOutcome.URL_BLOCKED
        || outcome == PriceSnapshotOutcome.REDIRECT_BLOCKED
        || outcome == PriceSnapshotOutcome.RESPONSE_TOO_LARGE
        || outcome == PriceSnapshotOutcome.UNSUPPORTED_CONTENT_TYPE
        || outcome == PriceSnapshotOutcome.DECOMPRESSION_FAILED
        || outcome == PriceSnapshotOutcome.CURRENCY_MISMATCH
        || outcome == PriceSnapshotOutcome.REQUIRES_JS
        || outcome == PriceSnapshotOutcome.BLOCKED;
  }

  private static TestPriceExtractionResponse.Outcome toDryRunOutcome(PriceSnapshotOutcome outcome) {
    return switch (outcome) {
      case FETCH_FAILED -> TestPriceExtractionResponse.Outcome.FETCH_FAILED;
      case PARSE_FAILED -> TestPriceExtractionResponse.Outcome.PARSE_FAILED;
      case CURRENCY_MISMATCH -> TestPriceExtractionResponse.Outcome.CURRENCY_MISMATCH;
      default -> TestPriceExtractionResponse.Outcome.BLOCKED;
    };
  }

  private String evidenceSnippet(String body, String url) {
    if (!storeSanitizedSnippet || body == null || isSensitiveUrl(url)) return null;
    String cleaned =
        body.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
            .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
            .replaceAll("(?is)<[^>]+>", " ")
            .replaceAll("[\\w.%+-]+@[\\w.-]+\\.[A-Za-z]{2,}", "[email]")
            .replaceAll("\\+?\\d[\\d\\s().-]{7,}\\d", "[phone]")
            .replaceAll("(?i)(token|secret|password|session|api[_-]?key)=\\S+", "$1=[redacted]")
            .replaceAll("\\s+", " ")
            .trim();
    return truncate(cleaned, 512);
  }

  private static boolean isSensitiveUrl(String url) {
    if (url == null) return false;
    String lower = url.toLowerCase(Locale.ROOT);
    return lower.contains("login")
        || lower.contains("account")
        || lower.contains("checkout")
        || lower.contains("payment")
        || lower.contains("cart");
  }

  private static String normalizeCurrency(String value) {
    if (value == null || value.isBlank()) return null;
    String currency = value.trim().toUpperCase(Locale.ROOT);
    return currency.matches("[A-Z]{3}") ? currency : null;
  }

  private static String normalizeMessage(String message) {
    if (message == null || message.isBlank()) return null;
    String out = message.replaceAll("https?://[^\\s]+", "[redacted-url]");
    return truncate(out, 240);
  }

  private static String truncate(String body, int max) {
    if (body == null) return null;
    return body.length() <= max ? body : body.substring(0, max);
  }

  private static String hash(String body) {
    if (body == null) return null;
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder();
      for (byte b : digest) out.append(String.format("%02x", b));
      return out.toString();
    } catch (Exception ex) {
      return null;
    }
  }
}
