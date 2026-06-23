package kz.hrms.splitupauth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommissionCalculatorTest {

    private CommissionCalculator calc;

    @BeforeEach
    void setUp() {
        calc = new CommissionCalculator();
        // Defaults from application.properties (the table on the pricing sheet).
        ReflectionTestUtils.setField(calc, "tier1Max", new BigDecimal("4000"));
        ReflectionTestUtils.setField(calc, "tier2Max", new BigDecimal("6000"));
        ReflectionTestUtils.setField(calc, "tier3Max", new BigDecimal("8000"));
        ReflectionTestUtils.setField(calc, "tier1Fee", new BigDecimal("500"));
        ReflectionTestUtils.setField(calc, "tier2Fee", new BigDecimal("700"));
        ReflectionTestUtils.setField(calc, "tier3Fee", new BigDecimal("900"));
        ReflectionTestUtils.setField(calc, "tier4Fee", new BigDecimal("1000"));
    }

    private void assertFee(String share, String expectedFee) {
        assertEquals(0, new BigDecimal(expectedFee).compareTo(calc.commissionFor(new BigDecimal(share))),
                "share " + share + " should map to fee " + expectedFee);
    }

    @Test
    void tiersAndBoundaries() {
        // Tier 1: up to and including 4000 → 500
        assertFee("1", "500");
        assertFee("1822.50", "500");
        assertFee("4000", "500");
        // Tier 2: 4001–6000 → 700
        assertFee("4000.01", "700");
        assertFee("6000", "700");
        // Tier 3: 6001–8000 → 900
        assertFee("6000.01", "900");
        assertFee("8000", "900");
        // Tier 4: above 8000 → 1000
        assertFee("8000.01", "1000");
        assertFee("12900", "1000");
    }

    @Test
    void nonPositiveShareYieldsZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(calc.commissionFor(BigDecimal.ZERO)));
        assertEquals(0, BigDecimal.ZERO.compareTo(calc.commissionFor(new BigDecimal("-5"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(calc.commissionFor(null)));
    }
}
