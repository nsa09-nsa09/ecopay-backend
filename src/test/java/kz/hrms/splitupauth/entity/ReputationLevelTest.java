package kz.hrms.splitupauth.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ReputationLevelTest {

  @Test
  void mapsScoresToTiersAtBoundaries() {
    // Lower bounds (inclusive) of each tier.
    assertEquals(ReputationLevel.NEWCOMER, ReputationLevel.fromScore(0));
    assertEquals(ReputationLevel.NEWCOMER, ReputationLevel.fromScore(19));
    assertEquals(ReputationLevel.BRONZE, ReputationLevel.fromScore(20));
    assertEquals(ReputationLevel.BRONZE, ReputationLevel.fromScore(39));
    assertEquals(ReputationLevel.SILVER, ReputationLevel.fromScore(40));
    assertEquals(ReputationLevel.SILVER, ReputationLevel.fromScore(59));
    assertEquals(ReputationLevel.GOLD, ReputationLevel.fromScore(60));
    assertEquals(ReputationLevel.GOLD, ReputationLevel.fromScore(79));
    assertEquals(ReputationLevel.PLATINUM, ReputationLevel.fromScore(80));
    assertEquals(ReputationLevel.PLATINUM, ReputationLevel.fromScore(100));
  }

  @Test
  void treatsNullAndNegativeAsNewcomer() {
    assertEquals(ReputationLevel.NEWCOMER, ReputationLevel.fromScore(null));
    assertEquals(ReputationLevel.NEWCOMER, ReputationLevel.fromScore(-5));
  }

  @Test
  void scoresAboveTopBandStayPlatinum() {
    assertEquals(ReputationLevel.PLATINUM, ReputationLevel.fromScore(150));
  }
}
