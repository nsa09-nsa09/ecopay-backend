package kz.hrms.splitupauth.service;

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

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    /** Stub the four activity signals that feed computeScore. */
    private void stub(User u, int reviewStars, int reviewCount,
                      long completedAsOwner, long completedAsMember, long violations) {
        List<Review> reviews = IntStream.range(0, reviewCount)
                .mapToObj(i -> Review.builder().rating(reviewStars).build())
                .toList();
        lenient().when(reviewRepository.findByRecipientAndHiddenByAdminFalseOrderByCreatedAtDesc(u))
                .thenReturn(reviews);
        lenient().when(roomRepository.countByOwnerAndStatusAndDeletedAtIsNull(u, RoomStatus.COMPLETED))
                .thenReturn(completedAsOwner);
        lenient().when(roomMemberRepository.countCompletedAsActiveMember(u))
                .thenReturn(completedAsMember);
        lenient().when(disputeRepository.countConfirmedViolationsAgainstOwner(u))
                .thenReturn(violations);
    }

    @Test
    void newUserWithNoActivityScoresZero() {
        User u = user();
        stub(u, 0, 0, 0, 0, 0);
        assertEquals(0, service.computeScore(u));
        assertEquals(ReputationLevel.NEWCOMER, service.levelOf(service.computeScore(u)));
    }

    @Test
    void ratingAloneContributesUpToHalf() {
        User u = user();
        stub(u, 5, 3, 0, 0, 0);   // 5★ avg → (5/5)*50 = 50
        assertEquals(50, service.computeScore(u));

        stub(u, 3, 2, 0, 0, 0);   // 3★ avg → 30
        assertEquals(30, service.computeScore(u));
    }

    @Test
    void completedRoomsRewardActivityAndCap() {
        User u = user();
        stub(u, 0, 0, 2, 1, 0);   // 3 completed rooms → 3*10 = 30, no reviews
        assertEquals(30, service.computeScore(u));
        assertEquals(ReputationLevel.BRONZE, ReputationLevel.fromScore(service.computeScore(u)));

        stub(u, 0, 0, 4, 3, 0);   // 7 completed → capped at 50
        assertEquals(50, service.computeScore(u));
    }

    @Test
    void fullActivityReachesPlatinum() {
        User u = user();
        stub(u, 5, 4, 3, 2, 0);   // 50 rating + 5*10 activity = 100
        int score = service.computeScore(u);
        assertEquals(100, score);
        assertEquals(ReputationLevel.PLATINUM, ReputationLevel.fromScore(score));
    }

    @Test
    void confirmedViolationsApplyPenalty() {
        User u = user();
        stub(u, 5, 2, 0, 0, 1);   // 50 rating - 15 penalty = 35
        assertEquals(35, service.computeScore(u));
        assertEquals(ReputationLevel.BRONZE, ReputationLevel.fromScore(service.computeScore(u)));
    }

    @Test
    void scoreIsClampedToZeroByPenalties() {
        User u = user();
        stub(u, 1, 1, 0, 0, 1);   // 10 rating - 15 penalty = -5 → clamped to 0
        assertEquals(0, service.computeScore(u));
    }

    @Test
    void recomputePersistsWhenScoreChanges() {
        User u = user();
        u.setReputation(0);
        stub(u, 5, 3, 0, 0, 0);   // → 50

        int result = service.recompute(u);

        assertEquals(50, result);
        assertEquals(50, u.getReputation());
        verify(userRepository).save(u);
    }

    @Test
    void recomputeSkipsSaveWhenScoreUnchanged() {
        User u = user();
        u.setReputation(50);
        stub(u, 5, 3, 0, 0, 0);   // recomputes to 50 — already current

        int result = service.recompute(u);

        assertEquals(50, result);
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
