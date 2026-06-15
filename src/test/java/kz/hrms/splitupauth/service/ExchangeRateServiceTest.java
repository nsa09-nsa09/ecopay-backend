package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the FX converter both in fallback mode (no network) and after
 * applying a synthetic USD-base payload. The fallback path is what production
 * runs on if the upstream provider is unreachable and what tests use to stay
 * hermetic.
 */
class ExchangeRateServiceTest {

    @Test
    void kztRateIsOneByDefault() {
        ExchangeRateService svc = new ExchangeRateService(new ObjectMapper());

        assertEquals(0, BigDecimal.ONE.compareTo(svc.rateOf("KZT")));
        assertEquals(BigDecimal.ZERO.setScale(2),
                svc.toKzt(BigDecimal.ZERO, "KZT").setScale(2));
        assertEquals(new BigDecimal("1500.00"),
                svc.toKzt(new BigDecimal("1500"), "KZT").setScale(2));
    }

    @Test
    void fallbackUsdRateConvertsApproximately() {
        ExchangeRateService svc = new ExchangeRateService(new ObjectMapper());

        // Default fallback: USD ≈ 475 KZT. 10 USD ~ 4750 KZT.
        BigDecimal asKzt = svc.toKzt(new BigDecimal("10"), "USD");
        assertEquals(new BigDecimal("4750.00"), asKzt);
    }

    @Test
    void appliesUsdBaseSourcePayload() throws Exception {
        ExchangeRateService svc = new ExchangeRateService(new ObjectMapper());

        // Synthetic open.er-api.com / exchangerate.host shape (rates as units-per-USD).
        String body = "{\"result\":\"success\",\"base_code\":\"USD\",\"rates\":{"
                + "\"USD\":1,\"KZT\":500,\"EUR\":0.9,\"RUB\":90,\"GBP\":0.8,"
                + "\"CNY\":7.2,\"UZS\":12500,\"KGS\":89}}";
        svc.applyFromUsdBase(body);

        // USD per 1 unit = 1/0.9 ≈ 1.1111 USD per EUR. KZT per EUR = 500 / 0.9 ≈ 555.56.
        BigDecimal eur = svc.rateOf("EUR");
        BigDecimal expectedEur = new BigDecimal("500").divide(new BigDecimal("0.9"), 6, RoundingMode.HALF_UP);
        assertEquals(expectedEur, eur);

        // Conversion sanity: 10 USD → 5000 KZT exactly.
        assertEquals(new BigDecimal("5000.00"), svc.toKzt(new BigDecimal("10"), "USD"));

        assertNotNull(svc.getUpdatedAt(), "updatedAt should advance after a successful apply");
        assertTrue(svc.getRatesToKzt().containsKey("CNY"));
    }
}
