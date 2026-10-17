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
 * Computes a user's reputation as a "trust" rating on a 10-point scale (stored ×10 as 0..100 so the
 * UI can render one decimal, e.g. 74 → 7.4/10).
 *
 * <p>The rating aggregates peer reviews:
 *
 * <ul>
 *   <li><b>No reviews yet</b> — the user sits at the neutral {@link User#DEFAULT_REPUTATION} (=
 *       5.0/10). This is a starting point, not an earned score.
 *   <li><b>With reviews</b> — the score is the average review rating (1..10) × 10.
 *   <li><b>Violation penalty</b> — {@value #VIOLATION_PENALTY} points per confirmed owner-fault
 *       violation (disputes ruled against the owner) are subtracted on top.
 * </ul>
 *
 * <p>Completed room counts are a separate informational metric on the profile — they are not part
 * of the trust score.
 */
@Service
@RequiredArgsConstructor
public class ReputationService {

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
    int base;
    if (reviews.isEmpty()) {
      base = User.DEFAULT_REPUTATION;
    } else {
      double avg = reviews.stream().mapToInt(r -> r.getRating()).average().orElse(0.0);
      // Guard against out-of-range data before scaling it into a score.
      double clampedAvg = Math.max(1.0, Math.min(10.0, avg));
      base = (int) Math.round(clampedAvg * 10.0);
    }

    long violations = disputeRepository.countConfirmedViolationsAgainstOwner(user);
    long violationPenalty = violations * VIOLATION_PENALTY;

    int score = (int) (base - violationPenalty);
    return Math.max(0, Math.min(100, score));
  }

  /** Derive the band for a stored score. */
  public ReputationLevel levelOf(Integer score) {
    return ReputationLevel.fromScore(score);
  }
}
