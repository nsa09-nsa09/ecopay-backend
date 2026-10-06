package kz.hrms.splitupauth.pricing;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import kz.hrms.splitupauth.dto.CreatePriceWatchProviderRequest;
import kz.hrms.splitupauth.dto.PriceChangeDto;
import kz.hrms.splitupauth.dto.PriceSnapshotDto;
import kz.hrms.splitupauth.dto.PriceWatchProviderDto;
import kz.hrms.splitupauth.dto.TestPriceExtractionRequest;
import kz.hrms.splitupauth.dto.TestPriceExtractionResponse;
import kz.hrms.splitupauth.dto.UpdatePriceWatchProviderRequest;
import kz.hrms.splitupauth.entity.PriceChange;
import kz.hrms.splitupauth.entity.PriceExtractorType;
import kz.hrms.splitupauth.entity.PriceWatchProvider;
import kz.hrms.splitupauth.entity.PriceWatchStatus;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.pricing.SafeOutboundUrlPolicy.UnsafeOutboundUrlException;
import kz.hrms.splitupauth.repository.PriceChangeRepository;
import kz.hrms.splitupauth.repository.PriceSnapshotRepository;
import kz.hrms.splitupauth.repository.PriceWatchProviderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin-facing facade for the Price Watch module: CRUD on providers, on-demand "check now", history
 * & change feed reads. Keeps the controller thin — all validation and mapping happens here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPricingService {

  private final PriceWatchProviderRepository providerRepository;
  private final PriceSnapshotRepository snapshotRepository;
  private final PriceChangeRepository changeRepository;
  private final PriceWatchService priceWatchService;
  private final PriceWatchMapper mapper;
  private final SafeOutboundUrlPolicy urlPolicy;

  @Value("${app.pricing.default-interval-minutes:720}")
  private int defaultIntervalMinutes;

  @Value("${app.pricing.min-interval-minutes:15}")
  private int minIntervalMinutes;

  @Value("${app.pricing.max-interval-minutes:43200}")
  private int maxIntervalMinutes;

  @Value("${app.pricing.max-price:1000000}")
  private java.math.BigDecimal maxPrice;

  @Transactional(readOnly = true)
  public List<PriceWatchProviderDto> listProviders() {
    return providerRepository.findAllByOrderByIdAsc().stream().map(mapper::toDto).toList();
  }

  @Transactional(readOnly = true)
  public PriceWatchProviderDto getProvider(Long id) {
    return mapper.toDto(loadProvider(id));
  }

  @Transactional
  public PriceWatchProviderDto createProvider(CreatePriceWatchProviderRequest req) {
    validateCreate(req);
    PriceWatchProvider p = new PriceWatchProvider();
    p.setPlatformCode(normalizeText(req.getPlatformCode()));
    p.setDisplayName(normalizeText(req.getDisplayName()));
    p.setPlanName(normalizeText(req.getPlanName()));
    p.setUrl(normalizeNullable(req.getUrl()));
    p.setLocale(normalizeNullable(req.getLocale()));
    p.setExpectedCurrency(normalizeCurrency(req.getExpectedCurrency()));
    p.setExtractorType(req.getExtractorType());
    p.setExtractorConfig(req.getExtractorConfig());
    p.setRequiresJs(req.getRequiresJs() != null && req.getRequiresJs());
    p.setCheckIntervalMinutes(
        req.getCheckIntervalMinutes() != null
            ? clampInterval(req.getCheckIntervalMinutes())
            : clampInterval(defaultIntervalMinutes));
    p.setActive(req.getActive() == null || req.getActive());
    p.setStatus(PriceWatchStatus.PENDING);
    p.setConsecutiveFailures(0);
    // Kick the scheduler on the next tick.
    p.setNextCheckAt(LocalDateTime.now());
    if (req.getInitialPrice() != null) {
      p.setLastPrice(req.getInitialPrice());
      p.setLastCurrency(
          req.getInitialCurrency() == null
              ? p.getExpectedCurrency()
              : normalizeCurrency(req.getInitialCurrency()));
    }
    return mapper.toDto(providerRepository.save(p));
  }

  @Transactional
  public PriceWatchProviderDto updateProvider(Long id, UpdatePriceWatchProviderRequest req) {
    PriceWatchProvider p = loadProvider(id);
    PriceExtractorType nextType =
        req.getExtractorType() == null ? p.getExtractorType() : req.getExtractorType();
    String nextUrl = req.getUrl() == null ? p.getUrl() : normalizeNullable(req.getUrl());
    validateProviderShape(nextType, nextUrl, req.getCheckIntervalMinutes());
    if (req.getExtractorConfig() != null)
      validateExtractorConfig(nextType, req.getExtractorConfig());
    if (req.getPlatformCode() != null) p.setPlatformCode(normalizeText(req.getPlatformCode()));
    if (req.getDisplayName() != null) p.setDisplayName(normalizeText(req.getDisplayName()));
    if (req.getPlanName() != null) p.setPlanName(normalizeText(req.getPlanName()));
    if (req.getUrl() != null) p.setUrl(nextUrl);
    if (req.getLocale() != null) p.setLocale(normalizeNullable(req.getLocale()));
    if (req.getExpectedCurrency() != null)
      p.setExpectedCurrency(normalizeCurrency(req.getExpectedCurrency()));
    if (req.getExtractorType() != null) p.setExtractorType(req.getExtractorType());
    if (req.getExtractorConfig() != null) p.setExtractorConfig(req.getExtractorConfig());
    if (req.getRequiresJs() != null) p.setRequiresJs(req.getRequiresJs());
    if (req.getCheckIntervalMinutes() != null)
      p.setCheckIntervalMinutes(clampInterval(req.getCheckIntervalMinutes()));
    if (req.getActive() != null) {
      p.setActive(req.getActive());
      // Re-enabling a failed provider: give it a clean slate so the scheduler
      // picks it up immediately instead of after N more silent failures.
      if (req.getActive()) {
        p.setConsecutiveFailures(0);
        if (p.getStatus() == PriceWatchStatus.FAILING) {
          p.setStatus(PriceWatchStatus.PENDING);
        }
        p.setNextCheckAt(LocalDateTime.now());
      }
    }
    // Manual price entry: applied only if the row is MANUAL (or the admin
    // simultaneously flipped extractorType to MANUAL) to avoid an accidental
    // overwrite of an auto-tracked price.
    if (req.getManualPrice() != null && p.getExtractorType() == PriceExtractorType.MANUAL) {
      validatePrice(req.getManualPrice());
      String manualCurrency =
          req.getManualCurrency() == null
              ? p.getExpectedCurrency()
              : normalizeCurrency(req.getManualCurrency());
      if (manualCurrency == null) {
        throw new InvalidRequestException("manualCurrency or expectedCurrency is required");
      }
      PriceChange change =
          PriceChange.builder()
              .provider(p)
              .oldPrice(p.getLastPrice())
              .newPrice(req.getManualPrice())
              .currency(manualCurrency)
              .build();
      p.setLastPrice(req.getManualPrice());
      p.setLastCurrency(manualCurrency);
      p.setLastSuccessAt(LocalDateTime.now());
      p.setStatus(PriceWatchStatus.OK);
      changeRepository.save(change);
    }
    return mapper.toDto(providerRepository.save(p));
  }

  @Transactional
  public void deleteProvider(Long id) {
    if (!providerRepository.existsById(id)) {
      throw new ResourceNotFoundException("provider not found: " + id);
    }
    providerRepository.deleteById(id);
  }

  /**
   * Synchronous "check now" for the admin table: runs one fetch → extract → persist cycle and
   * echoes the fresh provider row so the UI can render the new price/status without a second GET.
   * Bounded by {@code app.pricing.timeout-seconds} inside {@link PageFetcher}, so an unresponsive
   * upstream can't hang the request longer than that.
   */
  @Transactional
  public PriceWatchProviderDto checkNow(Long id) {
    // Existence check up front so a bad id fails 404 before we go on the network.
    loadProvider(id);
    return mapper.toDto(priceWatchService.checkProvider(id));
  }

  /** Dry-run the recipe against a URL without writing anything. Powers "Test URL" in the modal. */
  public TestPriceExtractionResponse testExtraction(TestPriceExtractionRequest req) {
    if (req.getExtractorType() == PriceExtractorType.MANUAL) {
      return priceWatchService.testExtraction(req);
    }
    validateSafeUrl(req.getUrl());
    validateExtractorConfig(req.getExtractorType(), req.getExtractorConfig());
    return priceWatchService.testExtraction(req);
  }

  /**
   * Fire-and-forget check — kept for the scheduler seam and any background caller. Runs on Spring's
   * default async executor. The admin "check now" endpoint uses {@link #checkNow} instead so it can
   * hand back the fresh row.
   */
  @Async
  public void triggerCheck(Long id) {
    try {
      priceWatchService.checkProvider(id);
    } catch (Exception ex) {
      log.warn("Admin-triggered check for provider {} failed: {}", id, ex.getMessage());
    }
  }

  @Transactional(readOnly = true)
  public List<PriceSnapshotDto> history(Long id, int limit) {
    PriceWatchProvider p = loadProvider(id);
    int cap = Math.max(1, Math.min(500, limit));
    return snapshotRepository
        .findByProviderOrderByCapturedAtDesc(p, PageRequest.of(0, cap))
        .stream()
        .map(mapper::toDto)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<PriceChangeDto> changes(boolean unacknowledgedOnly) {
    List<PriceChange> rows =
        unacknowledgedOnly
            ? changeRepository.findByAcknowledgedFalseOrderByChangedAtDesc()
            : changeRepository.findAllByOrderByChangedAtDesc();
    return rows.stream().map(mapper::toDto).toList();
  }

  @Transactional
  public PriceChangeDto acknowledgeChange(Long id) {
    PriceChange c =
        changeRepository
            .findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("change not found: " + id));
    c.setAcknowledged(true);
    return mapper.toDto(changeRepository.save(c));
  }

  private PriceWatchProvider loadProvider(Long id) {
    return providerRepository
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("provider not found: " + id));
  }

  private void validateCreate(CreatePriceWatchProviderRequest req) {
    validateProviderShape(
        req.getExtractorType(), normalizeNullable(req.getUrl()), req.getCheckIntervalMinutes());
    validateExtractorConfig(req.getExtractorType(), req.getExtractorConfig());
    if (req.getInitialPrice() != null) {
      validatePrice(req.getInitialPrice());
      if (normalizeCurrency(req.getInitialCurrency()) == null
          && normalizeCurrency(req.getExpectedCurrency()) == null) {
        throw new InvalidRequestException(
            "initialCurrency or expectedCurrency is required with initialPrice");
      }
    }
  }

  private void validateProviderShape(PriceExtractorType type, String url, Integer interval) {
    if (type == null) throw new InvalidRequestException("extractorType is required");
    if (type == PriceExtractorType.MANUAL) {
      return;
    }
    validateSafeUrl(url);
    if (interval != null) clampInterval(interval);
  }

  private void validateSafeUrl(String url) {
    try {
      urlPolicy.validate(url);
    } catch (UnsafeOutboundUrlException ex) {
      throw new InvalidRequestException(ex.getMessage());
    }
  }

  private void validateExtractorConfig(
      PriceExtractorType type, com.fasterxml.jackson.databind.JsonNode config) {
    if (config == null) return;
    switch (type) {
      case REGEX -> {
        String pattern = config.path("pattern").asText("");
        if (pattern.length() > 500) throw new InvalidRequestException("regex pattern is too long");
        int group = config.path("group").asInt(1);
        if (group < 0 || group > 8) throw new InvalidRequestException("regex group is invalid");
      }
      case CSS -> {
        String selector = config.path("selector").asText("");
        if (selector.length() > 300) throw new InvalidRequestException("CSS selector is too long");
      }
      default -> {}
    }
  }

  private int clampInterval(int minutes) {
    if (minutes < minIntervalMinutes || minutes > maxIntervalMinutes) {
      throw new InvalidRequestException("checkIntervalMinutes is outside the allowed range");
    }
    return minutes;
  }

  private void validatePrice(java.math.BigDecimal price) {
    if (price == null || price.signum() <= 0 || price.compareTo(maxPrice) > 0) {
      throw new InvalidRequestException("price is outside the allowed range");
    }
  }

  private static String normalizeText(String value) {
    String out = normalizeNullable(value);
    if (out == null) throw new InvalidRequestException("required text field is blank");
    return out;
  }

  private static String normalizeNullable(String value) {
    if (value == null) return null;
    String out = value.trim().replaceAll("\\s+", " ");
    return out.isBlank() ? null : out;
  }

  private static String normalizeCurrency(String value) {
    String out = normalizeNullable(value);
    if (out == null) return null;
    out = out.toUpperCase(Locale.ROOT);
    if (!out.matches("[A-Z]{3}"))
      throw new InvalidRequestException("currency must be an ISO-like code");
    return out;
  }
}
