package kz.hrms.splitupauth.pricing;

import java.time.LocalDateTime;
import java.util.List;
import kz.hrms.splitupauth.dto.PriceChangeDto;
import kz.hrms.splitupauth.dto.PriceSnapshotDto;
import kz.hrms.splitupauth.dto.PriceWatchProviderDto;
import kz.hrms.splitupauth.entity.PriceChange;
import kz.hrms.splitupauth.entity.PriceSnapshot;
import kz.hrms.splitupauth.entity.PriceWatchProvider;
import kz.hrms.splitupauth.repository.PriceChangeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Entity ↔ DTO translator. Kept separate from the service so the controller can hand back a DTO
 * without pulling in JPA relations at render time.
 */
@Component
@RequiredArgsConstructor
public class PriceWatchMapper {

  private final PriceChangeRepository changeRepository;

  public PriceWatchProviderDto toDto(PriceWatchProvider p) {
    List<PriceChange> recent = changeRepository.findByProviderOrderByChangedAtDesc(p);
    LocalDateTime lastChanged = recent.isEmpty() ? null : recent.get(0).getChangedAt();

    return PriceWatchProviderDto.builder()
        .id(idOf(p.getId()))
        .platformCode(p.getPlatformCode())
        .displayName(p.getDisplayName())
        .planName(p.getPlanName())
        .url(p.getUrl())
        .locale(p.getLocale())
        .expectedCurrency(p.getExpectedCurrency())
        .extractorType(p.getExtractorType())
        .extractorConfig(p.getExtractorConfig())
        .requiresJs(p.getRequiresJs())
        .checkIntervalMinutes(p.getCheckIntervalMinutes())
        .active(p.getActive())
        .status(p.getStatus())
        .consecutiveFailures(p.getConsecutiveFailures())
        .lastCheckedAt(p.getLastCheckedAt())
        .lastSuccessAt(p.getLastSuccessAt())
        .nextCheckAt(p.getNextCheckAt())
        .lastPrice(p.getLastPrice())
        .lastCurrency(p.getLastCurrency())
        .lastChangedAt(lastChanged)
        .createdAt(p.getCreatedAt())
        .updatedAt(p.getUpdatedAt())
        .build();
  }

  public PriceSnapshotDto toDto(PriceSnapshot s) {
    return PriceSnapshotDto.builder()
        .id(idOf(s.getId()))
        .providerId(s.getProvider() == null ? null : idOf(s.getProvider().getId()))
        .price(s.getPrice())
        .currency(s.getCurrency())
        .capturedAt(s.getCapturedAt())
        .outcome(s.getOutcome())
        .httpStatus(s.getHttpStatus())
        .errorMessage(s.getErrorMessage())
        .build();
  }

  public PriceChangeDto toDto(PriceChange c) {
    PriceWatchProvider p = c.getProvider();
    return PriceChangeDto.builder()
        .id(idOf(c.getId()))
        .providerId(p == null ? null : idOf(p.getId()))
        .providerName(p == null ? null : p.getDisplayName())
        .planName(p == null ? null : p.getPlanName())
        .oldPrice(c.getOldPrice())
        .newPrice(c.getNewPrice())
        .currency(c.getCurrency())
        .changedAt(c.getChangedAt())
        .snapshotId(c.getSnapshot() == null ? null : idOf(c.getSnapshot().getId()))
        .acknowledged(c.getAcknowledged())
        .build();
  }

  /**
   * CockroachDB's BIGSERIAL emits ids above 2^53. Emitting them as JSON strings keeps the browser
   * from silently rounding on {@code JSON.parse} — that rounding was the root cause of the "404
   * provider not found" the admin panel hit on every mutating call.
   */
  private static String idOf(Long value) {
    return value == null ? null : String.valueOf(value);
  }
}
