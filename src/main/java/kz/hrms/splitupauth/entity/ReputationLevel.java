package kz.hrms.splitupauth.entity;

/**
 * Trust band derived from the composite reputation score (0-100, i.e. the 10-point rating × 10).
 * "Reputation" here means "how much the platform trusts this user"; a user with no reviews starts
 * at the neutral {@link #FAIR} (score {@link User#DEFAULT_REPUTATION} = 5.0/10) and moves up or
 * down as peer reviews arrive.
 *
 * <p>The band is NOT persisted — it is a pure function of {@link User#getReputation()} so the tier
 * and the underlying score can never drift apart. Thresholds are inclusive lower bounds.
 */
public enum ReputationLevel {
  CRITICAL(0),
  LOW(20),
  FAIR(40),
  GOOD(70),
  EXCELLENT(90);

  private final int minScore;

  ReputationLevel(int minScore) {
    this.minScore = minScore;
  }

  public int getMinScore() {
    return minScore;
  }

  /** Map a composite reputation score to its band. Null or negative scores fall to CRITICAL. */
  public static ReputationLevel fromScore(Integer score) {
    int s = score == null ? 0 : score;
    ReputationLevel result = CRITICAL;
    for (ReputationLevel level : values()) {
      if (s >= level.minScore) {
        result = level;
      }
    }
    return result;
  }
}
