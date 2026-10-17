package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import kz.hrms.splitupauth.dto.CreateReviewRequest;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.PaymentTransactionStatus;
import kz.hrms.splitupauth.entity.Review;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.PaymentTransactionRepository;
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
 * B5 rewrite: reviews must be anchored to a shared room and only after the review window opens.
 * These tests exercise the author-is-owner + recipient-is-paid-member happy path, the upsert, and
 * the key rejection paths.
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceCreateTest {

  @Mock ReviewRepository reviewRepository;
  @Mock UserRepository userRepository;
  @Mock RoomRepository roomRepository;
  @Mock RoomMemberRepository roomMemberRepository;
  @Mock PaymentTransactionRepository paymentTransactionRepository;
  @Mock ReputationService reputationService;
  @Mock AvatarStorageService avatarStorageService;

  private ReviewService service() {
    return new ReviewService(
        reviewRepository,
        userRepository,
        roomRepository,
        roomMemberRepository,
        paymentTransactionRepository,
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

  /**
   * Author (room owner) rates recipient (paid member) in a COMPLETED room — the canonical happy
   * path after B5.
   */
  private Room setupSharedCompletedRoom(User owner, User member, Long roomId, Long memberRowId) {
    Room room = Room.builder().id(roomId).owner(owner).status(RoomStatus.COMPLETED).build();
    RoomMember rm =
        RoomMember.builder()
            .id(memberRowId)
            .room(room)
            .user(member)
            .status(MemberStatus.ACTIVE)
            .build();
    when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
    when(roomMemberRepository.findByRoomAndUserAndDeletedAtIsNull(room, member))
        .thenReturn(Optional.of(rm));
    when(paymentTransactionRepository.existsByRoomMember_IdAndStatus(
            eq(memberRowId), eq(PaymentTransactionStatus.SUCCESS)))
        .thenReturn(true);
    return room;
  }

  @Test
  void createReview_sharedCompletedRoom_persistsAndRecomputes() {
    User owner = user(1L);
    User member = user(2L);
    when(userRepository.findById(2L)).thenReturn(Optional.of(member));
    setupSharedCompletedRoom(owner, member, 10L, 100L);
    when(reviewRepository.findByAuthorAndRecipientAndRoom_Id(owner, member, 10L))
        .thenReturn(Optional.empty());
    when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

    service().createReview(owner, req(2L, 10L, 5, "great"));

    ArgumentCaptor<Review> cap = ArgumentCaptor.forClass(Review.class);
    verify(reviewRepository).save(cap.capture());
    Review saved = cap.getValue();
    assertSame(owner, saved.getAuthor());
    assertSame(member, saved.getRecipient());
    assertEquals(10L, saved.getRoom().getId(), "B5: review is anchored to the shared room");
    assertEquals(5, saved.getRating());
    verify(reputationService).recompute(member);
  }

  @Test
  void createReview_secondSubmission_updatesExistingRowInsteadOfCreatingSecond() {
    User owner = user(1L);
    User member = user(2L);
    when(userRepository.findById(2L)).thenReturn(Optional.of(member));
    Room room = setupSharedCompletedRoom(owner, member, 10L, 100L);
    Review existing =
        Review.builder()
            .id(99L)
            .author(owner)
            .recipient(member)
            .room(room)
            .rating(4)
            .text("okay")
            .build();

    when(reviewRepository.findByAuthorAndRecipientAndRoom_Id(owner, member, 10L))
        .thenReturn(Optional.of(existing));
    when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

    service().createReview(owner, req(2L, 10L, 2, "changed my mind"));

    ArgumentCaptor<Review> cap = ArgumentCaptor.forClass(Review.class);
    verify(reviewRepository).save(cap.capture());
    Review saved = cap.getValue();
    assertSame(existing, saved, "B5: upsert the (author, recipient, room) row, don't duplicate");
    assertEquals(2, saved.getRating());
    assertEquals("changed my mind", saved.getText());
    verify(reputationService).recompute(member);
  }

  @Test
  void createReview_selfReview_rejected() {
    User self = user(1L);
    CreateReviewRequest req = req(1L, 10L, 5, "flattering");

    assertThrows(InvalidRequestException.class, () -> service().createReview(self, req));
    verify(reviewRepository, never()).save(any());
    verify(reputationService, never()).recompute(any());
  }

  @Test
  void createReview_notSameRoom_rejected() {
    // B5: author and recipient must have shared the room. Here the recipient was never a member.
    User author = user(1L);
    User recipient = user(2L);
    Room room = Room.builder().id(10L).owner(user(99L)).status(RoomStatus.COMPLETED).build();
    when(userRepository.findById(2L)).thenReturn(Optional.of(recipient));
    when(roomRepository.findById(10L)).thenReturn(Optional.of(room));
    when(roomMemberRepository.findByRoomAndUserAndDeletedAtIsNull(room, author))
        .thenReturn(Optional.empty());

    assertThrows(
        ForbiddenOperationException.class,
        () -> service().createReview(author, req(2L, 10L, 5, "x")));
    verify(reviewRepository, never()).save(any());
    verify(reputationService, never()).recompute(any());
  }

  @Test
  void createReview_unknownRoom_404() {
    User author = user(1L);
    User recipient = user(2L);
    when(userRepository.findById(2L)).thenReturn(Optional.of(recipient));
    when(roomRepository.findById(10L)).thenReturn(Optional.empty());

    assertThrows(
        ResourceNotFoundException.class,
        () -> service().createReview(author, req(2L, 10L, 5, "x")));
    verify(reviewRepository, never()).save(any());
  }
}
