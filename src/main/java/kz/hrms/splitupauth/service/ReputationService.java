package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.entity.ReputationLevel;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.repository.DisputeRepository;
import kz.hrms.splitupauth.repository.ReviewRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes a user's activity-based reputation score and tier.
 *
 * <p>The score (0-100, persisted on {@link User#getReputation()}) is a composite of three signals:
 * <ul>
 *   <li><b>Rating</b> — average review rating, weighted up to {@value #RATING_MAX} points.</li>
 *   <li><b>Activity</b> — successfully completed rooms (as owner or active member),
 *       {@value #POINTS_PER_COMPLETED_ROOM} pts each up to {@value #ACTIVITY_MAX}.</li>
 *   <li><b>Penalty</b> — confirmed owner-fault violations, {@value #PENALTY_PER_VIOLATION} pts each.</li>
 * </ul>
 *
 * <p>The {@link ReputationLevel} tier is always derived from the score (never stored separately).
 */
@Service
@RequiredArgsConstructor
public class ReputationService {

    static final int RATING_MAX = 50;
    static final int ACTIVITY_MAX = 50;
    static final int POINTS_PER_COMPLETED_ROOM = 10;
    static final int PENALTY_PER_VIOLATION = 15;

    private final ReviewRepository reviewRepository;
    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final DisputeRepository disputeRepository;
    private final UserRepository userRepository;

    /** Recompute and persist a user's composite reputation score. Safe to call repeatedly. */
    @Transactional
    public int recompute(User user) {
        int score = computeScore(user);
        if (!Integer.valueOf(score).equals(user.getReputation())) {
            user.setReputation(score);
            userRepository.save(user);
        }
        return score;
    }

    /** Number of successfully completed rooms a user has, as owner or as an active member. */
    public long completedRoomsCount(User user) {
        long asOwner = roomRepository.countByOwnerAndStatusAndDeletedAtIsNull(user, RoomStatus.COMPLETED);
        long asMember = roomMemberRepository.countCompletedAsActiveMember(user);
        return asOwner + asMember;
    }

    /** Pure computation of the 0-100 composite score (does not persist). */
    public int computeScore(User user) {
        var reviews = reviewRepository.findByRecipientAndHiddenByAdminFalseOrderByCreatedAtDesc(user);
        double ratingScore = reviews.isEmpty()
                ? 0.0
                : (reviews.stream().mapToInt(r -> r.getRating()).average().orElse(0.0) / 5.0) * RATING_MAX;

        long completedRooms = completedRoomsCount(user);
        double activityScore = Math.min(completedRooms * POINTS_PER_COMPLETED_ROOM, ACTIVITY_MAX);

        long violations = disputeRepository.countConfirmedViolationsAgainstOwner(user);
        double penalty = violations * PENALTY_PER_VIOLATION;

        int score = (int) Math.round(ratingScore + activityScore - penalty);
        return Math.max(0, Math.min(100, score));
    }

    /** Derive the tier for a stored score. */
    public ReputationLevel levelOf(Integer score) {
        return ReputationLevel.fromScore(score);
    }
}
