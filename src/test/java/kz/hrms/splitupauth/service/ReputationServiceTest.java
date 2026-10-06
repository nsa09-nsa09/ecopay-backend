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
   * Stub the two signals that feed computeScore: peer reviews (1..10 ratings) and confirmed
   * violations. Completed rooms are informational only and do not affect the score.
   */
  private void stubRatings(User u, List<Integer> ratings, long violations) {
    List<Review> reviews = ratings.stream().map(r -> Review.builder().rating(r).build()).toList();
    lenient()
        .when(reviewRepository.findByRecipientAndHiddenByAdminFalseOrderByCreatedAtDesc(u))
        .thenReturn(reviews);
    lenient()
        .when(disputeRepository.countConfirmedViolationsAgainstOwner(u))
        .thenReturn(violations);
  }

  private void stub(User u, int rating, int reviewCount, long violations) {
    stubRatings(u, IntStream.range(0, reviewCount).map(i -> rating).boxed().toList(), violations);
  }

  @Test
  void newUserWithNoReviewsSitsAtNeutralDefault() {
    User u = user();
    stub(u, 0, 0, 0);
    assertEquals(User.DEFAULT_REPUTATION, service.computeScore(u));
    assertEquals(50, service.computeScore(u)); // 5.0/10
    assertEquals(ReputationLevel.FAIR, service.levelOf(service.computeScore(u)));
  }

  @Test
  void perfectTenAverageScoresHundred() {
    User u = user();
    stub(u, 10, 4, 0); // avg 10 → 100
    assertEquals(100, service.computeScore(u));
    assertEquals(ReputationLevel.EXCELLENT, ReputationLevel.fromScore(service.computeScore(u)));
  }

  @Test
  void averageIsScaledTimesTen() {
    User u = user();
    stub(u, 7, 4, 0); // avg 7 → 70
    assertEquals(70, service.computeScore(u));
    assertEquals(ReputationLevel.GOOD, ReputationLevel.fromScore(service.computeScore(u)));
  }

  @Test
  void mixedRatingsAverageWithRounding() {
    User u = user();
    stubRatings(u, List.of(10, 7), 0); // avg 8.5 → 85
    assertEquals(85, service.computeScore(u));

    stubRatings(u, List.of(10, 7, 4), 0); // avg 7.0 → 70
    assertEquals(70, service.computeScore(u));
  }

  @Test
  void addingANewRatingMovesTheAverage() {
    User u = user();
    stubRatings(u, List.of(6, 6), 0); // avg 6.0 → 60
    assertEquals(60, service.computeScore(u));

    stubRatings(u, List.of(6, 6, 9), 0); // avg 7.0 → 70
    assertEquals(70, service.computeScore(u));
  }

  @Test
  void allOnesLandAtTen() {
    User u = user();
    stub(u, 1, 3, 0); // avg 1 → 10 (1.0/10)
    assertEquals(10, service.computeScore(u));
    assertEquals(ReputationLevel.CRITICAL, ReputationLevel.fromScore(service.computeScore(u)));
  }

  @Test
  void violationSubtractsTwentyFromAverageScore() {
    User u = user();
    stub(u, 8, 3, 1); // 80 - 20 = 60
    assertEquals(60, service.computeScore(u));
    assertEquals(ReputationLevel.FAIR, ReputationLevel.fromScore(service.computeScore(u)));
  }

  @Test
  void violationAlsoLowersTheNoReviewDefault() {
    User u = user();
    stub(u, 0, 0, 1); // 50 - 20 = 30
    assertEquals(30, service.computeScore(u));
    assertEquals(ReputationLevel.LOW, ReputationLevel.fromScore(service.computeScore(u)));
  }

  @Test
  void manyViolationsClampToZero() {
    User u = user();
    stub(u, 1, 3, 5); // 10 - 100 = -90 → clamped to 0
    assertEquals(0, service.computeScore(u));
  }

  @Test
  void outOfRangeStoredRatingsAreClampedBeforeScaling() {
    User u = user();
    stubRatings(u, List.of(15), 0); // corrupt data — clamp avg to 10 → 100
    assertEquals(100, service.computeScore(u));

    stubRatings(u, List.of(0), 0); // corrupt data — clamp avg to 1 → 10
    assertEquals(10, service.computeScore(u));
  }

  @Test
  void recomputePersistsWhenScoreChanges() {
    User u = user();
    u.setReputation(50);
    stub(u, 7, 4, 0); // → 70

    int result = service.recompute(u);

    assertEquals(70, result);
    assertEquals(70, u.getReputation());
    verify(userRepository).save(u);
  }

  @Test
  void recomputeSkipsSaveWhenScoreUnchanged() {
    User u = user();
    u.setReputation(70);
    stub(u, 7, 4, 0); // recomputes to 70 — already current

    int result = service.recompute(u);

    assertEquals(70, result);
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
