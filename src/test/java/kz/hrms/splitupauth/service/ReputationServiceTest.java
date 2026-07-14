package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.IntStream;
import kz.hrms.splitupauth.entity.ReputationLevel;
import kz.hrms.splitupauth.entity.Review;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.repository.DisputeRepository;
import kz.hrms.splitupauth.repository.ReviewRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReputationServiceTest {

  @Mock ReviewRepository reviewRepository;
  @Mock RoomRepository roomRepository;
  @Mock RoomMemberRepository roomMemberRepository;
  @Mock DisputeRepository disputeRepository;
  @Mock UserRepository userRepository;

  @InjectMocks ReputationService service;

  private User user() {
    return User.builder().id(7L).build();
  }

  /**
   * Stub the two signals that feed computeScore under the smoothed trust model: reviews and
   * confirmed violations.
   */
  private void stub(User u, int reviewStars, int reviewCount, long violations) {
    List<Review> reviews =
        IntStream.range(0, reviewCount)
            .mapToObj(i -> Review.builder().rating(reviewStars).build())
            .toList();
    lenient()
        .when(reviewRepository.findByRecipientAndHiddenByAdminFalseOrderByCreatedAtDesc(u))
        .thenReturn(reviews);
    lenient()
        .when(disputeRepository.countConfirmedViolationsAgainstOwner(u))
        .thenReturn(violations);
  }

  @Test
  void newUserWithNoActivityStaysAtBaseline() {
    User u = user();
    stub(u, 0, 0, 0);
    assertEquals(100, service.computeScore(u));
    assertEquals(ReputationLevel.EXCELLENT, service.levelOf(service.computeScore(u)));
  }

  @Test
  void singleOneStarBarelyDentsScore() {
    // (10*5 + 1)/11 = 4.6364 → (3.6364/4)*100 ≈ 90.909 → round 91 — one bad review only nudges
    // the score down thanks to the prior, so the user stays in the EXCELLENT band (≥ 90).
    User u = user();
    stub(u, 1, 1, 0);
    assertEquals(91, service.computeScore(u));
    assertEquals(ReputationLevel.EXCELLENT, ReputationLevel.fromScore(service.computeScore(u)));
  }

  @Test
  void singleFiveStarStaysAtBaseline() {
    // (10*5 + 5)/11 = 5.0 → 100
    User u = user();
    stub(u, 5, 1, 0);
    assertEquals(100, service.computeScore(u));
  }

  @Test
  void fiveOneStarsLandInFair() {
    // (50 + 5)/15 = 3.6667 → (2.6667/4)*100 ≈ 66.667 → round 67
    User u = user();
    stub(u, 1, 5, 0);
    assertEquals(67, service.computeScore(u));
    assertEquals(ReputationLevel.FAIR, ReputationLevel.fromScore(service.computeScore(u)));
  }

  @Test
  void tenOneStarsLandInFairMiddle() {
    // (50 + 10)/20 = 3.0 → (2/4)*100 = 50
    User u = user();
    stub(u, 1, 10, 0);
    assertEquals(50, service.computeScore(u));
    assertEquals(ReputationLevel.FAIR, ReputationLevel.fromScore(service.computeScore(u)));
  }

  @Test
  void twentyOneStarsLandInLow() {
    // (50 + 20)/30 = 2.3333 → (1.3333/4)*100 ≈ 33.333 → round 33
    User u = user();
    stub(u, 1, 20, 0);
    assertEquals(33, service.computeScore(u));
    assertEquals(ReputationLevel.LOW, ReputationLevel.fromScore(service.computeScore(u)));
  }

  @Test
  void fiveStarsWithTwoViolationsFallToFair() {
    // effAvg stays 5.0 (all 5★ + all-5★ prior) → rating 100, minus 2 * 20 = 40 → 60
    User u = user();
    stub(u, 5, 4, 2);
    assertEquals(60, service.computeScore(u));
    assertEquals(ReputationLevel.FAIR, ReputationLevel.fromScore(service.computeScore(u)));
  }

  @Test
  void veryLowRatingWithManyViolationsClampsToZero() {
    // 20 * 1★ → 33, minus 5 * 20 = 100 penalty → -67, clamped to 0
    User u = user();
    stub(u, 1, 20, 5);
    assertEquals(0, service.computeScore(u));
  }

  @Test
  void recomputePersistsWhenScoreChanges() {
    User u = user();
    u.setReputation(100);
    stub(u, 1, 5, 0); // → 80: (50+5)/15 = 3.6667 → 66.67 → 67

    int result = service.recompute(u);

    assertEquals(67, result);
    assertEquals(67, u.getReputation());
    verify(userRepository).save(u);
  }

  @Test
  void recomputeSkipsSaveWhenScoreUnchanged() {
    User u = user();
    u.setReputation(67);
    stub(u, 1, 5, 0); // recomputes to 67 — already current

    int result = service.recompute(u);

    assertEquals(67, result);
    verify(userRepository, never()).save(any());
  }

  @Test
  void completedRoomsCountSumsOwnerAndMember() {
    User u = user();
    when(roomRepository.countByOwnerAndStatusAndDeletedAtIsNull(eq(u), eq(RoomStatus.COMPLETED)))
        .thenReturn(2L);
    when(roomMemberRepository.countCompletedAsActiveMember(u)).thenReturn(3L);

    assertEquals(5L, service.completedRoomsCount(u));
  }
}
