package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.hrms.splitupauth.entity.Currency;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Daily-refreshed FX rates expressed as "1 source unit = X KZT".
 *
 * <p>Lookups for KZT always return 1. For the rest we fetch USD-base rates
 * once a day from a free public source (open.er-api.com) and derive the
 * KZT-base figures by dividing each X/USD by KZT/USD. If the upstream is
 * unreachable, we fall back to a hand-curated table so the product stays
 * usable and tests never depend on a network round-trip.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRateService {

    private static final String DEFAULT_SOURCE_URL = "https://open.er-api.com/v6/latest/USD";

    /** Fallback rates, expressed as "1 unit of currency = X KZT" (mid-2026 approx). */
    private static final Map<String, BigDecimal> FALLBACK_RATES_TO_KZT = Map.of(
            "KZT", BigDecimal.ONE,
            "USD", new BigDecimal("475.00"),
            "EUR", new BigDecimal("520.00"),
            "RUB", new BigDecimal("5.30"),
            "GBP", new BigDecimal("605.00"),
            "CNY", new BigDecimal("66.00"),
            "UZS", new BigDecimal("0.038"),
            "KGS", new BigDecimal("5.40")
    );

    private final ObjectMapper objectMapper;

    @Value("${app.fx.source-url:" + DEFAULT_SOURCE_URL + "}")
    private String sourceUrl;

    @Value("${app.fx.timeout-seconds:5}")
    private long timeoutSeconds;

    private volatile Map<String, BigDecimal> cachedRates = FALLBACK_RATES_TO_KZT;
    private volatile LocalDateTime updatedAt = LocalDateTime.now();

    /**
     * Snapshot of rates as "1 currency unit = X KZT". The map always contains
     * every {@link Currency} (fallback used for anything the upstream omits).
     */
    public Map<String, BigDecimal> getRatesToKzt() {
        return cachedRates;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    /** Converts an amount in {@code currency} into KZT, scaled to 2 decimals. */
    public BigDecimal toKzt(BigDecimal amount, String currency) {
        if (amount == null) {
            return null;
        }
        BigDecimal rate = rateOf(currency);
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    /** Returns the snapshot rate currently in effect for {@code currency}; falls back to 1 for unknowns. */
    public BigDecimal rateOf(String currency) {
        if (currency == null || currency.isBlank()) {
            return BigDecimal.ONE;
        }
        Map<String, BigDecimal> rates = cachedRates;
        BigDecimal rate = rates.get(currency.toUpperCase());
        if (rate != null) {
            return rate;
        }
        BigDecimal fallback = FALLBACK_RATES_TO_KZT.get(currency.toUpperCase());
        return fallback != null ? fallback : BigDecimal.ONE;
    }

    /**
     * Refresh from upstream. Safe to call repeatedly; failures leave the
     * previous snapshot in place.
     */
    public void refresh() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(sourceUrl))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("FX refresh: upstream returned status {}; keeping previous snapshot", response.statusCode());
                return;
            }
            applyFromUsdBase(response.body());
        } catch (Exception ex) {
            log.warn("FX refresh failed ({}); keeping previous snapshot", ex.getMessage());
        }
    }

    /**
     * Parses an USD-base response (rates[USD]=1, rates[KZT]=455, ...) into our
     * KZT-base form by dividing each currency's USD rate by the KZT rate.
     * Visible for tests so they can exercise the conversion without a network.
     */
    void applyFromUsdBase(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode rates = root.has("rates") ? root.get("rates")
                    : (root.has("conversion_rates") ? root.get("conversion_rates") : null);
            if (rates == null || !rates.isObject()) {
                return;
            }
            JsonNode kztNode = rates.get("KZT");
            if (kztNode == null || kztNode.isNull()) {
                return;
            }
            BigDecimal kztPerUsd = kztNode.decimalValue();
            if (kztPerUsd.signum() <= 0) {
                return;
            }

            Map<String, BigDecimal> next = new LinkedHashMap<>();
            next.put("KZT", BigDecimal.ONE);
            for (Currency c : Currency.values()) {
                if (c == Currency.KZT) continue;
                String code = c.name();
                JsonNode node = rates.get(code);
                if (node != null && !node.isNull()) {
                    BigDecimal perUsd = node.decimalValue();
                    if (perUsd.signum() > 0) {
                        // X currency per USD; KZT per X = KZT/USD ÷ X/USD.
                        BigDecimal perX = kztPerUsd.divide(perUsd, MathContext.DECIMAL64)
                                .setScale(6, RoundingMode.HALF_UP);
                        next.put(code, perX);
                        continue;
                    }
                }
                next.put(code, FALLBACK_RATES_TO_KZT.getOrDefault(code, BigDecimal.ONE));
            }
            cachedRates = Map.copyOf(new HashMap<>(next));
            updatedAt = LocalDateTime.now();
        } catch (Exception ex) {
            log.warn("FX parse failed ({}); keeping previous snapshot", ex.getMessage());
        }
    }
}
