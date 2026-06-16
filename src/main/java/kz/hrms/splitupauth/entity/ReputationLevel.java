package kz.hrms.splitupauth.entity;

/**
 * Activity-based reputation tier, derived from the composite reputation score (0-100).
 *
 * <p>The level is NOT persisted — it is a pure function of {@link User#getReputation()} so the
 * tier and the underlying score can never drift apart. Thresholds are inclusive lower bounds.
 */
public enum ReputationLevel {
    NEWCOMER(0),
    BRONZE(20),
    SILVER(40),
    GOLD(60),
    PLATINUM(80);

    private final int minScore;

    ReputationLevel(int minScore) {
        this.minScore = minScore;
    }

    public int getMinScore() {
        return minScore;
    }

    /**
     * Map a composite reputation score to its tier. Null or negative scores fall to NEWCOMER.
     */
    public static ReputationLevel fromScore(Integer score) {
        int s = score == null ? 0 : score;
        ReputationLevel result = NEWCOMER;
        for (ReputationLevel level : values()) {
            if (s >= level.minScore) {
                result = level;
            }
        }
        return result;
    }
}
