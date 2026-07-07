package kz.hrms.splitupauth.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import kz.hrms.splitupauth.dto.TestPriceExtractionRequest;
import kz.hrms.splitupauth.dto.TestPriceExtractionResponse;
import kz.hrms.splitupauth.entity.PriceChange;
import kz.hrms.splitupauth.entity.PriceExtractorType;
import kz.hrms.splitupauth.entity.PriceSnapshot;
import kz.hrms.splitupauth.entity.PriceSnapshotOutcome;
import kz.hrms.splitupauth.entity.PriceWatchProvider;
import kz.hrms.splitupauth.entity.PriceWatchStatus;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the fetch → extract → persist cycle for one {@link PriceWatchProvider}.
 *
 * <p>Deliberately mirrors {@code ExchangeRateService}: no network calls at startup by default,
 * failures leave the previous snapshot in place, and the public API is small enough for both the
 * scheduler and the admin "check now" button to reuse it.
 *
 * <p>State machine (per provider):
 *
 * <ul>
 *   <li>SUCCESS  → status=OK, {@code consecutiveFailures=0}, {@code lastPrice/lastCurrency}
 *       updated, {@code lastSuccessAt=now}. Emits a {@link PriceChange} row if the value moved.
 *   <li>PARSE_FAILED / FETCH_FAILED → {@code consecutiveFailures++}. Once past
 *       {@code failureThreshold}, we flip {@code status=FAILING} and {@code active=false} — the
 *       admin has to re-enable after tweaking the extractor.
 *   <li>BLOCKED → status=BLOCKED, threshold not touched. Blocked usually means "manual price /
 *       needs headless"; the row stays active so admin can flip it to MANUAL and enter the number
 *       by hand.
 * </ul>
 */
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

  /**
   * Run one check cycle for a single provider. Persists a snapshot regardless of outcome. Returns
   * the resulting provider row so callers (e.g. the admin controller) can echo the fresh status.
   */
  @Transactional
  public PriceWatchProvider checkProvider(Long providerId) {
    PriceWatchProvider provider = providerRepository.findById(providerId)
        .orElseThrow(() -> new IllegalArgumentException("provider not found: " + providerId));
    return check(provider);
  }

  @Transactional
  public PriceWatchProvider check(PriceWatchProvider provider) {
    LocalDateTime now = LocalDateTime.now();
    provider.setLastCheckedAt(now);

    // Manual providers never get auto-fetched — an admin enters the price by
    // hand via PUT /providers/{id}. We still stamp lastCheckedAt so the row
    // stops showing as "never checked" in the admin table.
    if (provider.getExtractorType() == PriceExtractorType.MANUAL) {
      provider.setStatus(PriceWatchStatus.PENDING);
      provider.setNextCheckAt(scheduleNext(provider, now));
      return providerRepository.save(provider);
    }

    FetchResult fetch = pageFetcher.fetch(provider, null, null);
    if (fetch.notModified()) {
      // 304 — page identical; treat as a success ping. We do NOT create a
      // snapshot row because there is genuinely no new observation.
      provider.setLastSuccessAt(now);
      provider.setConsecutiveFailures(0);
      provider.setStatus(PriceWatchStatus.OK);
      provider.setNextCheckAt(scheduleNext(provider, now));
      return providerRepository.save(provider);
    }

    if (fetch.outcome() == PriceSnapshotOutcome.BLOCKED) {
      recordSnapshot(provider, null, null, PriceSnapshotOutcome.BLOCKED,
          fetch.httpStatus(), null, fetch.errorMessage());
      provider.setStatus(PriceWatchStatus.BLOCKED);
      provider.setNextCheckAt(scheduleNext(provider, now));
      return providerRepository.save(provider);
    }

    if (fetch.outcome() == PriceSnapshotOutcome.FETCH_FAILED) {
      recordSnapshot(provider, null, null, PriceSnapshotOutcome.FETCH_FAILED,
          fetch.httpStatus(), null, fetch.errorMessage());
      return markFailure(provider, now);
    }

    // SUCCESS with a body — run the strategy pipeline.
    FetchedPage page = fetch.page();
    Optional<ParsedPrice> parsed = runExtractor(provider, page);
    String excerpt = truncate(page.body(), 4000);

    if (parsed.isEmpty()) {
      recordSnapshot(provider, null, null, PriceSnapshotOutcome.PARSE_FAILED,
          page.status(), excerpt, "no price found by extractor "
              + provider.getExtractorType());
      return markFailure(provider, now);
    }

    ParsedPrice p = parsed.get();
    PriceSnapshot snapshot = recordSnapshot(provider, p.price(), p.currency(),
        PriceSnapshotOutcome.SUCCESS, page.status(), excerpt, null);

    detectChange(provider, p, snapshot);

    provider.setLastPrice(p.price());
    provider.setLastCurrency(p.currency());
    provider.setLastSuccessAt(now);
    provider.setConsecutiveFailures(0);
    provider.setStatus(PriceWatchStatus.OK);
    provider.setNextCheckAt(scheduleNext(provider, now));
    return providerRepository.save(provider);
  }

  /**
   * Dry-run the fetch + extract pipeline for a URL without touching any table. Same code paths as
   * {@link #check(PriceWatchProvider)}: builds a transient provider, delegates to the same
   * {@link PageFetcher} and the same extractor strategies, then reports back a compact verdict for
   * the admin's "Test URL" button.
   *
   * <p>Never persists a snapshot, change or provider row — the admin uses this to iterate on the
   * recipe until the parsed price looks right, then clicks Save.
   */
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
      // No URL to hit — MANUAL prices come from the admin's keyboard, not a page.
      // Surface that as PARSE_FAILED with a clear message so the UI can explain
      // why the button is a no-op in this mode.
      return TestPriceExtractionResponse.builder()
          .outcome(TestPriceExtractionResponse.Outcome.PARSE_FAILED)
          .message("MANUAL extractor cannot be tested against a URL")
          .build();
    }

    FetchResult fetch = pageFetcher.fetch(probe, null, null);
    if (fetch.notModified()) {
      // 304 without a body: no number to show, but the page is reachable. Treat
      // as success so the admin knows the URL + conditional headers are wired,
      // even if they can't see a price yet.
      return TestPriceExtractionResponse.builder()
          .outcome(TestPriceExtractionResponse.Outcome.SUCCESS)
          .httpStatus(fetch.httpStatus())
          .message("304 Not Modified — page unchanged")
          .build();
    }
    if (fetch.outcome() == PriceSnapshotOutcome.BLOCKED) {
      return TestPriceExtractionResponse.builder()
          .outcome(TestPriceExtractionResponse.Outcome.BLOCKED)
          .httpStatus(fetch.httpStatus())
          .message(fetch.errorMessage())
          .build();
    }
    if (fetch.outcome() == PriceSnapshotOutcome.FETCH_FAILED) {
      return TestPriceExtractionResponse.builder()
          .outcome(TestPriceExtractionResponse.Outcome.FETCH_FAILED)
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
    PriceExtractor extractor = switch (provider.getExtractorType()) {
      case JSON_LD -> jsonLdExtractor;
      case META -> metaExtractor;
      case CSS -> cssExtractor;
      case REGEX -> regexExtractor;
      case AUTO -> autoExtractor;
      case MANUAL -> null; // handled earlier
    };
    if (extractor == null) return Optional.empty();
    try {
      return extractor.extract(page, config);
    } catch (Exception ex) {
      log.warn("Extractor {} for provider {} threw: {}",
          provider.getExtractorType(), provider.getId(), ex.getMessage());
      return Optional.empty();
    }
  }

  /** Package-private for tests: bump failure counter and flip status if we crossed the line. */
  PriceWatchProvider markFailure(PriceWatchProvider provider, LocalDateTime now) {
    int fails = Optional.ofNullable(provider.getConsecutiveFailures()).orElse(0) + 1;
    provider.setConsecutiveFailures(fails);
    if (fails >= failureThreshold) {
      provider.setStatus(PriceWatchStatus.FAILING);
      // Snap the row off the schedule so we stop battering a permanently
      // broken URL — admin has to re-enable after fixing the recipe.
      provider.setActive(false);
      log.warn("Price provider {} disabled after {} consecutive failures",
          provider.getId(), fails);
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
      String errorMessage) {
    PriceSnapshot snap = PriceSnapshot.builder()
        .provider(provider)
        .price(price)
        .currency(currency)
        .outcome(outcome)
        .httpStatus(httpStatus)
        .rawExcerpt(excerpt)
        .errorMessage(errorMessage)
        .build();
    return snapshotRepository.save(snap);
  }

  private void detectChange(PriceWatchProvider provider, ParsedPrice p, PriceSnapshot snap) {
    BigDecimal old = provider.getLastPrice();
    if (old == null) {
      // First-ever successful observation: record a change from null so the
      // admin's change feed shows the initial baseline.
      changeRepository.save(PriceChange.builder()
          .provider(provider)
          .oldPrice(null)
          .newPrice(p.price())
          .currency(p.currency())
          .snapshot(snap)
          .build());
      return;
    }
    if (old.compareTo(p.price()) != 0) {
      changeRepository.save(PriceChange.builder()
          .provider(provider)
          .oldPrice(old)
          .newPrice(p.price())
          .currency(p.currency())
          .snapshot(snap)
          .build());
    }
  }

  private LocalDateTime scheduleNext(PriceWatchProvider provider, LocalDateTime from) {
    int minutes = provider.getCheckIntervalMinutes() == null
        ? defaultIntervalMinutes : provider.getCheckIntervalMinutes();
    if (minutes < 1) minutes = 1;
    // Jitter ±15% so a fleet of providers with identical intervals doesn't fire
    // in lockstep and hammer the same upstream at the same second.
    int jitter = (int) Math.round(minutes * 0.15 * (ThreadLocalRandom.current().nextDouble() * 2 - 1));
    return from.plusMinutes(minutes + jitter);
  }

  private static String truncate(String body, int max) {
    if (body == null) return null;
    return body.length() <= max ? body : body.substring(0, max);
  }
}
