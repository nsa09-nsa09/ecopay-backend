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
 * Computes a user's reputation as a "trust" score using a Bayesian-smoothed rating.
 *
 * <p>The score (0-100, persisted on {@link User#getReputation()}) is derived from two signals:
 *
 * <ul>
 *   <li><b>Effective average rating</b> — a prior of {@value #PRIOR_WEIGHT} pseudo-reviews at
 *       {@value #PRIOR_MEAN}★ is blended with the real reviews. This anchors new profiles at the
 *       baseline and dampens the impact of a single low rating (Uber-style smoothing), so one 1★
 *       review only nudges the score down instead of collapsing it.
 *   <li><b>Violation penalty</b> — {@value #VIOLATION_PENALTY} points per confirmed owner-fault
 *       violation. This is a hard signal (disputes ruled against the owner) and applies on top of
 *       the smoothed rating.
 * </ul>
 *
 * <p>Formally: {@code effAvg = (PRIOR_WEIGHT * PRIOR_MEAN + Σ rating) / (PRIOR_WEIGHT + n)}, then
 * {@code ratingScore = (effAvg - 1) / 4 * 100} and finally {@code score = clamp(round(ratingScore)
 * - violations * VIOLATION_PENALTY, 0, 100)}. The baseline {@link #BASELINE_SCORE} of 100 falls out
 * naturally for {@code n = 0}.
 *
 * <p>Completed room counts are a separate informational metric on the profile — they are not part
 * of the trust score.
 */
@Service
@RequiredArgsConstructor
public class ReputationService {

  static final int BASELINE_SCORE = 100;
  static final int PRIOR_WEIGHT = 10;
  static final double PRIOR_MEAN = 5.0;
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
    int n = reviews.size();
    int sum = reviews.stream().mapToInt(r -> r.getRating()).sum();

    double effAvg = (PRIOR_WEIGHT * PRIOR_MEAN + sum) / (PRIOR_WEIGHT + n);
    double ratingScore = ((effAvg - 1.0) / 4.0) * 100.0;

    long violations = disputeRepository.countConfirmedViolationsAgainstOwner(user);
    long violationPenalty = violations * VIOLATION_PENALTY;

    int score = (int) Math.round(ratingScore) - (int) violationPenalty;
    return Math.max(0, Math.min(BASELINE_SCORE, score));
  }

  /** Derive the band for a stored score. */
  public ReputationLevel levelOf(Integer score) {
    return ReputationLevel.fromScore(score);
  }
}
