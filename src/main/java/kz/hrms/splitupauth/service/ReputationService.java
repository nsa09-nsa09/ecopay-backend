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
 * Computes a user's reputation as a "trust" score.
 *
 * <p>Everyone starts at {@value #BASELINE_SCORE}. The score can only decrease, from two signals:
 *
 * <ul>
 *   <li><b>Rating penalty</b> — proportional to how far the average review rating is from a perfect
 *       5★. A user with all 5★ reviews loses 0 points; all 1★ costs {@value
 *       #RATING_PENALTY_WEIGHT}.
 *   <li><b>Violation penalty</b> — {@value #VIOLATION_PENALTY} points per confirmed owner-fault
 *       violation (disputes ruled against the owner).
 * </ul>
 *
 * <p>Completed room counts remain a separate informational metric on the profile — they are not
 * part of the trust score.
 */
@Service
@RequiredArgsConstructor
public class ReputationService {

  static final int BASELINE_SCORE = 100;
  static final int RATING_PENALTY_WEIGHT = 50;
  static final int VIOLATION_PENALTY = 20;

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
    long asOwner =
        roomRepository.countByOwnerAndStatusAndDeletedAtIsNull(user, RoomStatus.COMPLETED);
    long asMember = roomMemberRepository.countCompletedAsActiveMember(user);
    return asOwner + asMember;
  }

  /** Pure computation of the 0-100 composite score (does not persist). */
  public int computeScore(User user) {
    var reviews = reviewRepository.findByRecipientAndHiddenByAdminFalseOrderByCreatedAtDesc(user);
    int ratingPenalty;
    if (reviews.isEmpty()) {
      ratingPenalty = 0;
    } else {
      double avg = reviews.stream().mapToInt(r -> r.getRating()).average().orElse(0.0);
      // Guard against out-of-range data before turning it into a penalty.
      double clampedAvg = Math.max(1.0, Math.min(5.0, avg));
      ratingPenalty = (int) Math.round(((5.0 - clampedAvg) / 4.0) * RATING_PENALTY_WEIGHT);
    }

    long violations = disputeRepository.countConfirmedViolationsAgainstOwner(user);
    long violationPenalty = violations * VIOLATION_PENALTY;

    int score = (int) (BASELINE_SCORE - ratingPenalty - violationPenalty);
    return Math.max(0, Math.min(100, score));
  }

  /** Derive the band for a stored score. */
  public ReputationLevel levelOf(Integer score) {
    return ReputationLevel.fromScore(score);
  }
}
