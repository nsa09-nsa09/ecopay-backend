package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
    ReflectionTestUtils.setField(calc, "mixedRoomMarketplaceFee", new BigDecimal("450"));
  }

  private void assertFee(String share, String expectedFee) {
    assertEquals(
        0,
        new BigDecimal(expectedFee).compareTo(calc.commissionFor(new BigDecimal(share))),
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

  @Test
  void mixedRoomUsesFixedMarketplaceFeeWithoutChangingLegacyMethod() {
    assertEquals(0, new BigDecimal("500.00").compareTo(calc.commissionFor(new BigDecimal("1500"))));
    assertEquals(
        0,
        new BigDecimal("450.00").compareTo(calc.commissionFor(new BigDecimal("1500"), 2)));
  }

  @Test
  void mixedRoomControlCaseBalancesAllThreeMarketplaceSeats() {
    BigDecimal share = new BigDecimal("7500.00").divide(BigDecimal.valueOf(5));
    BigDecimal fee = calc.commissionFor(share, 2);
    BigDecimal seats = BigDecimal.valueOf(3);
    assertEquals(new BigDecimal("1500.00"), share);
    assertEquals(new BigDecimal("450.00"), fee);
    assertEquals(new BigDecimal("1950.00"), share.add(fee));
    assertEquals(new BigDecimal("5850.00"), share.add(fee).multiply(seats));
    assertEquals(new BigDecimal("4500.00"), share.multiply(seats));
    assertEquals(new BigDecimal("1350.00"), fee.multiply(seats));
    assertEquals(new BigDecimal("450.00"), calc.commissionFor(new BigDecimal("9000"), 2));
  }
}
