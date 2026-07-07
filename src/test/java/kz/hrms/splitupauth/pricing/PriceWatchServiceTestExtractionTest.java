package kz.hrms.splitupauth.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Collections;
import kz.hrms.splitupauth.dto.TestPriceExtractionRequest;
import kz.hrms.splitupauth.dto.TestPriceExtractionResponse;
import kz.hrms.splitupauth.entity.PriceChange;
import kz.hrms.splitupauth.entity.PriceExtractorType;
import kz.hrms.splitupauth.entity.PriceSnapshot;
import kz.hrms.splitupauth.entity.PriceWatchProvider;
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
 * Drives {@link PriceWatchService#testExtraction} through a stubbed fetcher. The dry-run must
 * exercise the same fetch → extract path as the scheduled check but leave the repositories
 * untouched — no snapshot, no change row, no provider save.
 */
class PriceWatchServiceTestExtractionTest {

  private PriceWatchProviderRepository providerRepo;
  private PriceSnapshotRepository snapshotRepo;
  private PriceChangeRepository changeRepo;
  private PageFetcher fetcher;
  private PriceWatchService service;

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
  }

  private TestPriceExtractionRequest request(PriceExtractorType type) {
    TestPriceExtractionRequest req = new TestPriceExtractionRequest();
    req.setUrl("https://example.test/plan");
    req.setExtractorType(type);
    return req;
  }

  private FetchedPage pageWithBody(String body) {
    return new FetchedPage(
        "https://example.test/plan", 200, body, Collections.emptyMap(), null, "en");
  }

  @Test
  void success_returnsParsedPrice_andPersistsNothing() {
    when(fetcher.fetch(any(), any(), any()))
        .thenReturn(FetchResult.ok(pageWithBody("<div>Only $12.50 per month</div>"), null, null));

    TestPriceExtractionResponse out = service.testExtraction(request(PriceExtractorType.AUTO));

    assertEquals(TestPriceExtractionResponse.Outcome.SUCCESS, out.getOutcome());
    assertNotNull(out.getPrice());
    assertEquals(0, new BigDecimal("12.50").compareTo(out.getPrice()));
    assertEquals("USD", out.getCurrency());
    assertEquals(Integer.valueOf(200), out.getHttpStatus());
    // Belt-and-braces: dry-run must not touch any repository.
    verify(providerRepo, never()).save(any(PriceWatchProvider.class));
    verify(snapshotRepo, never()).save(any(PriceSnapshot.class));
    verify(changeRepo, never()).save(any(PriceChange.class));
  }

  @Test
  void parseFailed_whenExtractorFindsNothing() {
    when(fetcher.fetch(any(), any(), any()))
        .thenReturn(FetchResult.ok(pageWithBody("<div>no numbers here</div>"), null, null));

    TestPriceExtractionResponse out = service.testExtraction(request(PriceExtractorType.AUTO));

    assertEquals(TestPriceExtractionResponse.Outcome.PARSE_FAILED, out.getOutcome());
    assertNull(out.getPrice());
    assertNotNull(out.getMessage());
    verify(snapshotRepo, never()).save(any(PriceSnapshot.class));
  }

  @Test
  void fetchFailed_isReported() {
    when(fetcher.fetch(any(), any(), any()))
        .thenReturn(FetchResult.fetchFailed(504, "gateway timeout"));

    TestPriceExtractionResponse out = service.testExtraction(request(PriceExtractorType.AUTO));

    assertEquals(TestPriceExtractionResponse.Outcome.FETCH_FAILED, out.getOutcome());
    assertNull(out.getPrice());
    assertEquals(Integer.valueOf(504), out.getHttpStatus());
    assertEquals("gateway timeout", out.getMessage());
    verify(snapshotRepo, never()).save(any(PriceSnapshot.class));
    verify(providerRepo, never()).save(any(PriceWatchProvider.class));
  }

  @Test
  void blocked_isReported_withHttpStatus() {
    when(fetcher.fetch(any(), any(), any()))
        .thenReturn(FetchResult.blocked(403, "http 403"));

    TestPriceExtractionResponse out = service.testExtraction(request(PriceExtractorType.AUTO));

    assertEquals(TestPriceExtractionResponse.Outcome.BLOCKED, out.getOutcome());
    assertEquals(Integer.valueOf(403), out.getHttpStatus());
    assertEquals("http 403", out.getMessage());
    verify(snapshotRepo, never()).save(any(PriceSnapshot.class));
  }

  @Test
  void manualExtractor_shortCircuits_withoutFetching() {
    TestPriceExtractionResponse out = service.testExtraction(request(PriceExtractorType.MANUAL));

    assertEquals(TestPriceExtractionResponse.Outcome.PARSE_FAILED, out.getOutcome());
    assertNotNull(out.getMessage());
    verify(fetcher, never()).fetch(any(), any(), any());
    verify(snapshotRepo, never()).save(any(PriceSnapshot.class));
  }
}
