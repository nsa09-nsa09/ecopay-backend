package kz.hrms.splitupauth.repository;

/** Aggregated review stats for a single recipient (room owner). */
public interface OwnerRatingProjection {
    Long getRecipientId();
    Double getAvgRating();
    Long getReviewCount();
}
