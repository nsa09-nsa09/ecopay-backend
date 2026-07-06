package kz.hrms.splitupauth.controller;

import java.time.Duration;
import kz.hrms.splitupauth.dto.FxRatesResponse;
import kz.hrms.splitupauth.service.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fx")
@RequiredArgsConstructor
public class FxRateController {

  private final ExchangeRateService exchangeRateService;

  @Value("${app.fx.response-cache-seconds:900}")
  private long cacheSeconds;

  /**
   * Public live-conversion source for the SPA. Cache-Control lets the browser / CDN reuse the
   * snapshot for {@code app.fx.response-cache-seconds} — the upstream refresh cadence (default 6h)
   * leaves plenty of room for intermediate caching without serving stale data.
   */
  @GetMapping("/rates")
  public ResponseEntity<FxRatesResponse> rates() {
    FxRatesResponse body =
        FxRatesResponse.builder()
            .base("KZT")
            .updatedAt(exchangeRateService.getUpdatedAt())
            .rates(exchangeRateService.getRatesToKzt())
            .build();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofSeconds(cacheSeconds)).cachePublic())
        .body(body);
  }
}
