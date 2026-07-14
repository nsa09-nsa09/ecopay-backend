package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import kz.hrms.splitupauth.dto.CreateReviewRequest;
import kz.hrms.splitupauth.entity.Review;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.repository.ReviewRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers the open-review flow: any authenticated user may rate any other user with or without a
 * room, and repeating the rating updates the existing row (upsert) instead of duplicating it.
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceCreateTest {

  @Mock ReviewRepository reviewRepository;
  @Mock UserRepository userRepository;
  @Mock RoomRepository roomRepository;
  @Mock RoomMemberRepository roomMemberRepository;
  @Mock ReputationService reputationService;
  @Mock AvatarStorageService avatarStorageService;

  private ReviewService service() {
    return new ReviewService(
        reviewRepository,
        userRepository,
        roomRepository,
        roomMemberRepository,
        reputationService,
        avatarStorageService);
  }

  private User user(long id) {
    return User.builder().id(id).displayName("u" + id).build();
  }

  private CreateReviewRequest req(Long recipientId, Long roomId, int rating, String text) {
    CreateReviewRequest r = new CreateReviewRequest();
    r.setRecipientId(recipientId);
    r.setRoomId(roomId);
    r.setRating(rating);
    r.setText(text);
    return r;
  }

  @Test
  void createReview_withoutRoom_persistsRoomlessReviewAndRecomputes() {
    User author = user(1L);
    User recipient = user(2L);
    when(userRepository.findById(2L)).thenReturn(Optional.of(recipient));
    when(reviewRepository.findFirstByAuthorAndRecipientOrderByCreatedAtDesc(author, recipient))
        .thenReturn(Optional.empty());
    when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

    service().createReview(author, req(2L, null, 5, "great"));

    ArgumentCaptor<Review> cap = ArgumentCaptor.forClass(Review.class);
    verify(reviewRepository).save(cap.capture());
    Review saved = cap.getValue();
    assertSame(author, saved.getAuthor());
    assertSame(recipient, saved.getRecipient());
    assertNull(saved.getRoom(), "roomless review has no room binding");
    assertEquals(5, saved.getRating());
    assertEquals("great", saved.getText());
    verify(reputationService).recompute(recipient);
    // No lookup into rooms is attempted when roomId is absent.
    verify(roomRepository, never()).findById(any());
  }

  @Test
  void createReview_secondSubmission_updatesExistingRowInsteadOfCreatingSecond() {
    User author = user(1L);
    User recipient = user(2L);
    Review existing =
        Review.builder().id(99L).author(author).recipient(recipient).rating(4).text("okay").build();

    when(userRepository.findById(2L)).thenReturn(Optional.of(recipient));
    when(reviewRepository.findFirstByAuthorAndRecipientOrderByCreatedAtDesc(author, recipient))
        .thenReturn(Optional.of(existing));
    when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

    service().createReview(author, req(2L, null, 2, "changed my mind"));

    // Same row is saved — no new instance created.
    ArgumentCaptor<Review> cap = ArgumentCaptor.forClass(Review.class);
    verify(reviewRepository).save(cap.capture());
    Review saved = cap.getValue();
    assertSame(existing, saved, "must upsert the same row, not create a second review");
    assertEquals(2, saved.getRating());
    assertEquals("changed my mind", saved.getText());
    verify(reputationService).recompute(recipient);
  }

  @Test
  void createReview_selfReview_rejected() {
    User self = user(1L);
    CreateReviewRequest req = req(1L, null, 5, "flattering");

    assertThrows(InvalidRequestException.class, () -> service().createReview(self, req));
    verify(reviewRepository, never()).save(any());
    verify(reputationService, never()).recompute(any());
  }

  @Test
  void createReview_alwaysCallsRecompute() {
    User author = user(1L);
    User recipient = user(2L);
    when(userRepository.findById(2L)).thenReturn(Optional.of(recipient));
    when(reviewRepository.findFirstByAuthorAndRecipientOrderByCreatedAtDesc(author, recipient))
        .thenReturn(Optional.empty());
    when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

    service().createReview(author, req(2L, null, 3, null));

    verify(reputationService).recompute(recipient);
  }
}
