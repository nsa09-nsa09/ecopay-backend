package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import kz.hrms.splitupauth.dto.CreateReviewRequest;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReviewWindowTest {

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

  private CreateReviewRequest req(Long recipientId, Long roomId) {
    CreateReviewRequest r = new CreateReviewRequest();
    r.setRecipientId(recipientId);
    r.setRoomId(roomId);
    r.setRating(5);
    r.setText("good");
    return r;
  }

  @Test
  void windowOpens_whenMemberHasActivatedAt_noMonthWait() {
    User owner = user(1L);
    User memberUser = user(2L);
    Room room = Room.builder().id(10L).owner(owner).status(RoomStatus.ACTIVE).build();
    RoomMember member =
        RoomMember.builder()
            .id(100L)
            .room(room)
            .user(memberUser)
            .status(MemberStatus.ACTIVE)
            .activatedAt(LocalDateTime.now().minusMinutes(5))
            .build();

    when(userRepository.findById(2L)).thenReturn(Optional.of(memberUser));
    when(roomRepository.findById(10L)).thenReturn(Optional.of(room));
    when(roomMemberRepository.findByRoomAndUserAndDeletedAtIsNull(room, memberUser))
        .thenReturn(Optional.of(member));
    when(paymentTransactionRepository.existsByRoomMember_IdAndStatus(
            eq(100L), eq(PaymentTransactionStatus.SUCCESS)))
        .thenReturn(true);
    when(reviewRepository.findByAuthorAndRecipientAndRoom_Id(owner, memberUser, 10L))
        .thenReturn(Optional.empty());
    when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

    assertDoesNotThrow(() -> service().createReview(owner, req(2L, 10L)));
  }

  @Test
  void windowOpens_whenMemberEndedParticipation() {
    User owner = user(1L);
    User memberUser = user(2L);
    Room room = Room.builder().id(10L).owner(owner).status(RoomStatus.ACTIVE).build();
    RoomMember member =
        RoomMember.builder()
            .id(100L)
            .room(room)
            .user(memberUser)
            .status(MemberStatus.ACTIVE)
            .endedAt(LocalDateTime.now().minusDays(1))
            .build();

    when(userRepository.findById(2L)).thenReturn(Optional.of(memberUser));
    when(roomRepository.findById(10L)).thenReturn(Optional.of(room));
    when(roomMemberRepository.findByRoomAndUserAndDeletedAtIsNull(room, memberUser))
        .thenReturn(Optional.of(member));
    when(paymentTransactionRepository.existsByRoomMember_IdAndStatus(
            eq(100L), eq(PaymentTransactionStatus.SUCCESS)))
        .thenReturn(true);
    when(reviewRepository.findByAuthorAndRecipientAndRoom_Id(owner, memberUser, 10L))
        .thenReturn(Optional.empty());
    when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

    assertDoesNotThrow(() -> service().createReview(owner, req(2L, 10L)));
  }

  @Test
  void windowClosed_whenMemberNotActivatedAndNotEnded() {
    User owner = user(1L);
    User memberUser = user(2L);
    Room room = Room.builder().id(10L).owner(owner).status(RoomStatus.ACTIVE).build();
    RoomMember member =
        RoomMember.builder()
            .id(100L)
            .room(room)
            .user(memberUser)
            .status(MemberStatus.PENDING)
            .build();

    when(userRepository.findById(2L)).thenReturn(Optional.of(memberUser));
    when(roomRepository.findById(10L)).thenReturn(Optional.of(room));
    when(roomMemberRepository.findByRoomAndUserAndDeletedAtIsNull(room, memberUser))
        .thenReturn(Optional.of(member));
    when(paymentTransactionRepository.existsByRoomMember_IdAndStatus(
            eq(100L), eq(PaymentTransactionStatus.SUCCESS)))
        .thenReturn(true);

    assertThrows(
        ForbiddenOperationException.class, () -> service().createReview(owner, req(2L, 10L)));
  }

  @Test
  void twoMembers_bothActivated_canReviewEachOther() {
    User owner = user(1L);
    User member1User = user(2L);
    User member2User = user(3L);
    Room room = Room.builder().id(10L).owner(owner).status(RoomStatus.ACTIVE).build();
    RoomMember m1 =
        RoomMember.builder()
            .id(100L)
            .room(room)
            .user(member1User)
            .status(MemberStatus.ACTIVE)
            .activatedAt(LocalDateTime.now().minusDays(2))
            .build();
    RoomMember m2 =
        RoomMember.builder()
            .id(101L)
            .room(room)
            .user(member2User)
            .status(MemberStatus.ACTIVE)
            .activatedAt(LocalDateTime.now().minusDays(1))
            .build();

    when(userRepository.findById(3L)).thenReturn(Optional.of(member2User));
    when(roomRepository.findById(10L)).thenReturn(Optional.of(room));
    when(roomMemberRepository.findByRoomAndUserAndDeletedAtIsNull(room, member1User))
        .thenReturn(Optional.of(m1));
    when(roomMemberRepository.findByRoomAndUserAndDeletedAtIsNull(room, member2User))
        .thenReturn(Optional.of(m2));
    when(paymentTransactionRepository.existsByRoomMember_IdAndStatus(
            eq(100L), eq(PaymentTransactionStatus.SUCCESS)))
        .thenReturn(true);
    when(paymentTransactionRepository.existsByRoomMember_IdAndStatus(
            eq(101L), eq(PaymentTransactionStatus.SUCCESS)))
        .thenReturn(true);
    when(reviewRepository.findByAuthorAndRecipientAndRoom_Id(member1User, member2User, 10L))
        .thenReturn(Optional.empty());
    when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

    assertDoesNotThrow(() -> service().createReview(member1User, req(3L, 10L)));
  }

  @Test
  void twoMembers_oneNotActivated_cannotReview() {
    User owner = user(1L);
    User member1User = user(2L);
    User member2User = user(3L);
    Room room = Room.builder().id(10L).owner(owner).status(RoomStatus.ACTIVE).build();
    RoomMember m1 =
        RoomMember.builder()
            .id(100L)
            .room(room)
            .user(member1User)
            .status(MemberStatus.ACTIVE)
            .activatedAt(LocalDateTime.now().minusDays(2))
            .build();
    RoomMember m2 =
        RoomMember.builder()
            .id(101L)
            .room(room)
            .user(member2User)
            .status(MemberStatus.PENDING)
            .build();

    when(userRepository.findById(3L)).thenReturn(Optional.of(member2User));
    when(roomRepository.findById(10L)).thenReturn(Optional.of(room));
    when(roomMemberRepository.findByRoomAndUserAndDeletedAtIsNull(room, member1User))
        .thenReturn(Optional.of(m1));
    when(roomMemberRepository.findByRoomAndUserAndDeletedAtIsNull(room, member2User))
        .thenReturn(Optional.of(m2));
    when(paymentTransactionRepository.existsByRoomMember_IdAndStatus(
            eq(100L), eq(PaymentTransactionStatus.SUCCESS)))
        .thenReturn(true);
    when(paymentTransactionRepository.existsByRoomMember_IdAndStatus(
            eq(101L), eq(PaymentTransactionStatus.SUCCESS)))
        .thenReturn(true);

    assertThrows(
        ForbiddenOperationException.class, () -> service().createReview(member1User, req(3L, 10L)));
  }
}
