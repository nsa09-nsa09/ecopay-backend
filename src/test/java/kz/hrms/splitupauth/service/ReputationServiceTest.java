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
   * Stub the two signals that feed computeScore under the trust model: reviews and confirmed
   * violations. Completed rooms are informational only and no longer affect the score.
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
  void perfectRatingKeepsScoreAtBaseline() {
    User u = user();
    stub(u, 5, 4, 0); // avg 5 → 0 rating penalty
    assertEquals(100, service.computeScore(u));
    assertEquals(ReputationLevel.EXCELLENT, ReputationLevel.fromScore(service.computeScore(u)));
  }

  @Test
  void averageThreeStarPenalizesTwentyFive() {
    User u = user();
    stub(u, 3, 4, 0); // (5 - 3)/4 * 50 = 25 penalty
    assertEquals(75, service.computeScore(u));
    assertEquals(ReputationLevel.GOOD, ReputationLevel.fromScore(service.computeScore(u)));
  }

  @Test
  void allOneStarCostsFullRatingWeight() {
    User u = user();
    stub(u, 1, 3, 0); // (5 - 1)/4 * 50 = 50 penalty
    assertEquals(50, service.computeScore(u));
    assertEquals(ReputationLevel.FAIR, ReputationLevel.fromScore(service.computeScore(u)));
  }

  @Test
  void oneViolationCompoundsRatingPenalty() {
    User u = user();
    stub(u, 1, 3, 1); // 50 - 20 = 30
    assertEquals(30, service.computeScore(u));
    assertEquals(ReputationLevel.LOW, ReputationLevel.fromScore(service.computeScore(u)));
  }

  @Test
  void twoViolationsWithOneStarLandInCritical() {
    User u = user();
    stub(u, 1, 3, 2); // 50 - 40 = 10
    assertEquals(10, service.computeScore(u));
    assertEquals(ReputationLevel.CRITICAL, ReputationLevel.fromScore(service.computeScore(u)));
  }

  @Test
  void manyViolationsClampToZero() {
    User u = user();
    stub(u, 1, 3, 5); // 50 - 100 = -50 → clamped to 0
    assertEquals(0, service.computeScore(u));
  }

  @Test
  void recomputePersistsWhenScoreChanges() {
    User u = user();
    u.setReputation(100);
    stub(u, 3, 4, 0); // → 75

    int result = service.recompute(u);

    assertEquals(75, result);
    assertEquals(75, u.getReputation());
    verify(userRepository).save(u);
  }

  @Test
  void recomputeSkipsSaveWhenScoreUnchanged() {
    User u = user();
    u.setReputation(75);
    stub(u, 3, 4, 0); // recomputes to 75 — already current

    int result = service.recompute(u);

    assertEquals(75, result);
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
