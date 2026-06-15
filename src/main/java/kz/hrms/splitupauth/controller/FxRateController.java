package kz.hrms.splitupauth.controller;

import kz.hrms.splitupauth.dto.FxRatesResponse;
import kz.hrms.splitupauth.service.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fx")
@RequiredArgsConstructor
public class FxRateController {

    private final ExchangeRateService exchangeRateService;

    @GetMapping("/rates")
    public ResponseEntity<FxRatesResponse> rates() {
        return ResponseEntity.ok(FxRatesResponse.builder()
                .base("KZT")
                .updatedAt(exchangeRateService.getUpdatedAt())
                .rates(exchangeRateService.getRatesToKzt())
                .build());
    }
}
