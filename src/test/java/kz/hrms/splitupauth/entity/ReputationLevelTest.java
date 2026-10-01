package kz.hrms.splitupauth.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ReputationLevelTest {

  @Test
  void mapsScoresToBandsAtBoundaries() {
    // Lower bounds (inclusive) of each trust band.
    assertEquals(ReputationLevel.CRITICAL, ReputationLevel.fromScore(0));
    assertEquals(ReputationLevel.CRITICAL, ReputationLevel.fromScore(19));
    assertEquals(ReputationLevel.LOW, ReputationLevel.fromScore(20));
    assertEquals(ReputationLevel.LOW, ReputationLevel.fromScore(39));
    assertEquals(ReputationLevel.FAIR, ReputationLevel.fromScore(40));
    assertEquals(ReputationLevel.FAIR, ReputationLevel.fromScore(69));
    assertEquals(ReputationLevel.GOOD, ReputationLevel.fromScore(70));
    assertEquals(ReputationLevel.GOOD, ReputationLevel.fromScore(89));
    assertEquals(ReputationLevel.EXCELLENT, ReputationLevel.fromScore(90));
    assertEquals(ReputationLevel.EXCELLENT, ReputationLevel.fromScore(100));
  }

  @Test
  void treatsNullAndNegativeAsCritical() {
    assertEquals(ReputationLevel.CRITICAL, ReputationLevel.fromScore(null));
    assertEquals(ReputationLevel.CRITICAL, ReputationLevel.fromScore(-5));
  }

  @Test
  void scoresAboveTopBandStayExcellent() {
    assertEquals(ReputationLevel.EXCELLENT, ReputationLevel.fromScore(150));
  }
}
