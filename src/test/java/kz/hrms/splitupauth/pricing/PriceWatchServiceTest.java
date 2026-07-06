package kz.hrms.splitupauth.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
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
import kz.hrms.splitupauth.pricing.extractor.RegexExtractor;
import kz.hrms.splitupauth.repository.PriceChangeRepository;
import kz.hrms.splitupauth.repository.PriceSnapshotRepository;
import kz.hrms.splitupauth.repository.PriceWatchProviderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Drives {@link PriceWatchService} through a stubbed fetcher: no HTTP, no DB. Verifies that a
 * price move is recorded as a {@link PriceChange} row and that N consecutive fetch failures flip
 * the provider off.
 */
class PriceWatchServiceTest {

  private PriceWatchProviderRepository providerRepo;
  private PriceSnapshotRepository snapshotRepo;
  private PriceChangeRepository changeRepo;
  private PageFetcher fetcher;
  private PriceWatchService service;

  private final List<PriceSnapshot> savedSnapshots = new ArrayList<>();
  private final List<PriceChange> savedChanges = new ArrayList<>();

  @BeforeEach
  void setUp() {
    providerRepo = mock(PriceWatchProviderRepository.class);
    snapshotRepo = mock(PriceSnapshotRepository.class);
    changeRepo = mock(PriceChangeRepository.class);
    fetcher = mock(PageFetcher.class);
    ObjectMapper mapper = new ObjectMapper();
    JsonLdExtractor jsonLd = new JsonLdExtractor(mapper);
    MetaTagExtractor meta = new MetaTagExtractor();
    CssSelectorExtractor css = new CssSelectorExtractor();
    RegexExtractor regex = new RegexExtractor();
    AutoExtractor auto = new AutoExtractor(jsonLd, meta, css, regex);

    service = new PriceWatchService(providerRepo, snapshotRepo, changeRepo, fetcher, jsonLd, meta,
        css, regex, auto);
    ReflectionTestUtils.setField(service, "failureThreshold", 3);
    ReflectionTestUtils.setField(service, "defaultIntervalMinutes", 720);

    when(providerRepo.save(any(PriceWatchProvider.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(snapshotRepo.save(any(PriceSnapshot.class))).thenAnswer(inv -> {
      PriceSnapshot s = inv.getArgument(0);
      s.setId((long) (savedSnapshots.size() + 1));
      savedSnapshots.add(s);
      return s;
    });
    when(changeRepo.save(any(PriceChange.class))).thenAnswer(inv -> {
      PriceChange c = inv.getArgument(0);
      c.setId((long) (savedChanges.size() + 1));
      savedChanges.add(c);
      return c;
    });
  }

  private PriceWatchProvider newProvider() {
    return PriceWatchProvider.builder()
        .id(1L)
        .platformCode("test")
        .displayName("Test")
        .planName("Basic")
        .url("https://example.test/")
        .extractorType(PriceExtractorType.AUTO)
        .active(true)
        .status(PriceWatchStatus.PENDING)
        .checkIntervalMinutes(60)
        .consecutiveFailures(0)
        .build();
  }

  private FetchedPage pageWithPrice(String priceLine) {
    String body = "<html><body>" + priceLine + "</body></html>";
    return new FetchedPage("https://example.test/", 200, body,
        Collections.emptyMap(), null, "en");
  }

  @Test
  void firstSuccessfulObservation_recordsBaselineChange() {
    PriceWatchProvider p = newProvider();
    when(fetcher.fetch(any(), any(), any()))
        .thenReturn(FetchResult.ok(pageWithPrice("<div>Only $9.99 per month</div>"), null, null));

    PriceWatchProvider out = service.check(p);

    assertEquals(PriceWatchStatus.OK, out.getStatus());
    assertEquals(0, new BigDecimal("9.99").compareTo(out.getLastPrice()));
    assertEquals("USD", out.getLastCurrency());
    assertEquals(1, savedSnapshots.size());
    assertEquals(PriceSnapshotOutcome.SUCCESS, savedSnapshots.get(0).getOutcome());
    assertEquals(1, savedChanges.size(), "first baseline should emit a change row");
    assertNull(savedChanges.get(0).getOldPrice(), "old price is null on the baseline");
    assertNotNull(out.getNextCheckAt());
  }

  @Test
  void detectsPriceMove_asChange() {
    PriceWatchProvider p = newProvider();
    p.setLastPrice(new BigDecimal("9.99"));
    p.setLastCurrency("USD");

    when(fetcher.fetch(any(), any(), any()))
        .thenReturn(FetchResult.ok(pageWithPrice("<div>Only $11.49 per month</div>"), null, null));

    PriceWatchProvider out = service.check(p);

    assertEquals(1, savedChanges.size());
    assertEquals(0, new BigDecimal("9.99").compareTo(savedChanges.get(0).getOldPrice()));
    assertEquals(0, new BigDecimal("11.49").compareTo(savedChanges.get(0).getNewPrice()));
    assertEquals(0, new BigDecimal("11.49").compareTo(out.getLastPrice()));
    assertEquals(PriceWatchStatus.OK, out.getStatus());
  }

  @Test
  void unchangedPrice_doesNotEmitChange() {
    PriceWatchProvider p = newProvider();
    p.setLastPrice(new BigDecimal("9.99"));
    p.setLastCurrency("USD");

    when(fetcher.fetch(any(), any(), any()))
        .thenReturn(FetchResult.ok(pageWithPrice("<div>Only $9.99 per month</div>"), null, null));

    service.check(p);

    assertEquals(0, savedChanges.size(),
        "identical price observation must not produce a change row");
    assertEquals(1, savedSnapshots.size(),
        "snapshot must still be recorded even when the price is unchanged");
  }

  @Test
  void repeatedFailures_flipProviderToFailingAndDeactivate() {
    PriceWatchProvider p = newProvider();

    // Every fetch fails with a network error.
    when(fetcher.fetch(any(), any(), any()))
        .thenReturn(FetchResult.fetchFailed(null, "connect timed out"));

    service.check(p); // fail 1
    service.check(p); // fail 2
    service.check(p); // fail 3 (== threshold set in @BeforeEach)

    assertEquals(3, p.getConsecutiveFailures());
    assertEquals(PriceWatchStatus.FAILING, p.getStatus());
    assertFalse(p.getActive(),
        "provider must be auto-deactivated once the failure threshold is reached");

    // Three FETCH_FAILED snapshots persisted.
    assertEquals(3, savedSnapshots.size());
    for (PriceSnapshot s : savedSnapshots) {
      assertEquals(PriceSnapshotOutcome.FETCH_FAILED, s.getOutcome());
    }
  }

  @Test
  void successAfterFailures_resetsCounterAndStatus() {
    PriceWatchProvider p = newProvider();
    p.setConsecutiveFailures(2);
    p.setStatus(PriceWatchStatus.STALE);

    when(fetcher.fetch(any(), any(), any()))
        .thenReturn(FetchResult.ok(pageWithPrice("<div>$4.99</div>"), null, null));

    PriceWatchProvider out = service.check(p);
    assertEquals(0, out.getConsecutiveFailures());
    assertEquals(PriceWatchStatus.OK, out.getStatus());
    assertTrue(out.getActive());
  }

  @Test
  void manualProvider_isNeverFetched() {
    PriceWatchProvider p = newProvider();
    p.setExtractorType(PriceExtractorType.MANUAL);

    PriceWatchProvider out = service.check(p);

    verify(fetcher, times(0)).fetch(any(), any(), any());
    assertEquals(PriceWatchStatus.PENDING, out.getStatus());
    assertNotNull(out.getLastCheckedAt(),
        "even manual providers get their lastCheckedAt bumped so admin sees activity");
  }

  @Test
  void blockedOutcome_setsBlockedStatus_withoutIncrementingFailures() {
    PriceWatchProvider p = newProvider();
    when(fetcher.fetch(any(), any(), any()))
        .thenReturn(FetchResult.blocked(403, "http 403"));

    PriceWatchProvider out = service.check(p);

    assertEquals(PriceWatchStatus.BLOCKED, out.getStatus());
    assertEquals(0, out.getConsecutiveFailures(),
        "BLOCKED means the site turned us away; it is not a permanent failure signal");
    assertTrue(out.getActive(), "blocked providers stay enabled so admin can flip to MANUAL");
    assertEquals(1, savedSnapshots.size());
    assertEquals(PriceSnapshotOutcome.BLOCKED, savedSnapshots.get(0).getOutcome());
  }

  @Test
  void requiresJsProvider_getsBlockedSnapshotFromFetcher() {
    // Sanity check that the fetcher stub follows the same contract as the real
    // one for requires_js=true: BLOCKED outcome, no crash.
    PriceWatchProvider p = newProvider();
    p.setRequiresJs(true);
    when(fetcher.fetch(any(), any(), any()))
        .thenReturn(FetchResult.blocked(null, "requires_js=true"));

    PriceWatchProvider out = service.check(p);
    assertEquals(PriceWatchStatus.BLOCKED, out.getStatus());
    Optional<PriceSnapshot> only = savedSnapshots.stream().findFirst();
    assertTrue(only.isPresent());
    assertEquals(PriceSnapshotOutcome.BLOCKED, only.get().getOutcome());
  }

  @Test
  void nextCheckAt_isSetInTheFuture() {
    PriceWatchProvider p = newProvider();
    when(fetcher.fetch(any(), any(), any()))
        .thenReturn(FetchResult.ok(pageWithPrice("<div>$1.00</div>"), null, null));
    LocalDateTime before = LocalDateTime.now();

    PriceWatchProvider out = service.check(p);

    assertNotNull(out.getNextCheckAt());
    assertTrue(out.getNextCheckAt().isAfter(before),
        "next check must be scheduled after the current time");
  }
}
