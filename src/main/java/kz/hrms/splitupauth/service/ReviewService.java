package kz.hrms.splitupauth.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kz.hrms.splitupauth.dto.CreateReviewRequest;
import kz.hrms.splitupauth.dto.ReputationDto;
import kz.hrms.splitupauth.dto.ReviewDto;
import kz.hrms.splitupauth.dto.ReviewEligibilityDto;
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
import kz.hrms.splitupauth.util.TextSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReviewService {

  private final ReviewRepository reviewRepository;
  private final UserRepository userRepository;
  private final RoomRepository roomRepository;
  private final RoomMemberRepository roomMemberRepository;
  private final PaymentTransactionRepository paymentTransactionRepository;
  private final ReputationService reputationService;
  private final AvatarStorageService avatarStorageService;

  @Transactional
  public ReviewDto createReview(User author, CreateReviewRequest req) {
    if (author.getId().equals(req.getRecipientId())) {
      throw new InvalidRequestException("Cannot review yourself");
    }

    User recipient =
        userRepository
            .findById(req.getRecipientId())
            .orElseThrow(() -> new ResourceNotFoundException("Recipient not found"));

    Room room =
        roomRepository
            .findById(req.getRoomId())
            .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

    // B5: author and recipient must have shared this room, and the review window must be open
    // (period closed, participation ended, or the room completed). Attempting to rate a stranger
    // from the search page or to rate while the room is still running fails here.
    if (!canReview(author, recipient, room)) {
      throw new ForbiddenOperationException(
          "REVIEW_NOT_ALLOWED",
          "Оценить можно только участника вашей комнаты после завершения периода");
    }

    String sanitizedText = TextSanitizer.sanitize(req.getText());

    // Upsert by (author, recipient, room): one rating per shared room. The old upsert was keyed
    // only on (author, recipient) which meant leaving a review in room A overwrote the one left
    // in room B — now rooms get independent reviews.
    Review review =
        reviewRepository
            .findByAuthorAndRecipientAndRoom_Id(author, recipient, room.getId())
            .orElse(null);
    if (review == null) {
      review =
          Review.builder()
              .author(author)
              .recipient(recipient)
              .room(room)
              .rating(req.getRating())
              .text(sanitizedText)
              .build();
    } else {
      review.setRating(req.getRating());
      review.setText(sanitizedText);
    }
    review = reviewRepository.save(review);

    reputationService.recompute(recipient);

    return ReviewDto.from(review);
  }

  /**
   * B5 eligibility probe for the frontend "leave a review" button. Walks the rooms where author and
   * recipient have both participated and the review window is open.
   */
  @Transactional(readOnly = true)
  public ReviewEligibilityDto getEligibility(User author, Long recipientId) {
    if (author.getId().equals(recipientId)) {
      return ReviewEligibilityDto.builder().eligible(false).rooms(List.of()).build();
    }
    User recipient =
        userRepository
            .findById(recipientId)
            .orElseThrow(() -> new ResourceNotFoundException("Recipient not found"));

    // Candidate rooms: every room the author participated in (as owner or paid member), live and
    // not soft-deleted. For each, check that the recipient was also a participant and that the
    // review window is open.
    Map<Long, ReviewEligibilityDto.EligibleRoom> hits = new LinkedHashMap<>();
    for (Room room : roomsWithParticipation(author)) {
      if (hits.containsKey(room.getId())) continue;
      if (!canReview(author, recipient, room)) continue;
      hits.put(
          room.getId(),
          ReviewEligibilityDto.EligibleRoom.builder()
              .roomId(room.getId())
              .roomTitle(room.getTitle())
              .build());
    }
    return ReviewEligibilityDto.builder()
        .eligible(!hits.isEmpty())
        .rooms(new ArrayList<>(hits.values()))
        .build();
  }

  private List<Room> roomsWithParticipation(User user) {
    List<Room> owned = roomRepository.findByOwnerAndDeletedAtIsNullOrderByCreatedAtDesc(user);
    List<Room> asMember = new ArrayList<>();
    for (RoomMember m :
        roomMemberRepository.findByUserAndDeletedAtIsNullOrderByCreatedAtDesc(user)) {
      if (m.getRoom() != null && m.getRoom().getDeletedAt() == null) {
        asMember.add(m.getRoom());
      }
    }
    List<Room> all = new ArrayList<>(owned);
    all.addAll(asMember);
    return all;
  }

  private boolean canReview(User author, User recipient, Room room) {
    if (room == null || room.getDeletedAt() != null) return false;
    boolean authorParticipated = participated(room, author);
    boolean recipientParticipated = participated(room, recipient);
    if (!authorParticipated || !recipientParticipated) return false;
    return reviewWindowOpen(room, author, recipient);
  }

  private boolean participated(Room room, User user) {
    if (room.getOwner() != null && room.getOwner().getId().equals(user.getId())) {
      return true;
    }
    RoomMember member =
        roomMemberRepository.findByRoomAndUserAndDeletedAtIsNull(room, user).orElse(null);
    if (member == null) return false;
    return paymentTransactionRepository.existsByRoomMember_IdAndStatus(
        member.getId(), PaymentTransactionStatus.SUCCESS);
  }

  /**
   * The review window opens when: - the room is COMPLETED, OR - a non-owner participant has
   * activatedAt set (access confirmed), OR - a non-owner participant has ended their participation
   * after payment, OR - between two non-owner participants of the same room, if both have
   * activatedAt.
   *
   * <p>No month wait required.
   */
  private boolean reviewWindowOpen(Room room, User author, User recipient) {
    if (room.getStatus() == RoomStatus.COMPLETED) return true;

    boolean authorIsOwner =
        room.getOwner() != null && room.getOwner().getId().equals(author.getId());
    boolean recipientIsOwner =
        room.getOwner() != null && room.getOwner().getId().equals(recipient.getId());

    // Between two non-owner participants: both must have activatedAt.
    if (!authorIsOwner && !recipientIsOwner) {
      RoomMember authorMember =
          roomMemberRepository.findByRoomAndUserAndDeletedAtIsNull(room, author).orElse(null);
      RoomMember recipientMember =
          roomMemberRepository.findByRoomAndUserAndDeletedAtIsNull(room, recipient).orElse(null);
      if (authorMember == null || recipientMember == null) return false;
      return authorMember.getActivatedAt() != null && recipientMember.getActivatedAt() != null;
    }

    // One side is the owner, the other is a member.
    User nonOwner = authorIsOwner ? recipient : author;
    if (room.getOwner() != null && room.getOwner().getId().equals(nonOwner.getId())) {
      return false;
    }
    RoomMember member =
        roomMemberRepository.findByRoomAndUserAndDeletedAtIsNull(room, nonOwner).orElse(null);
    if (member == null) return false;

    if (member.getActivatedAt() != null) return true;
    if (member.getEndedAt() != null) return true;

    return false;
  }

  @Transactional(readOnly = true)
  public List<ReviewDto> listForRecipient(Long userId) {
    var recipient =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    // B5: exclude null-room reviews from the public listing — they came from the pre-fix path
    // where any user could rate any other user without a shared room.
    return reviewRepository
        .findByRecipientAndHiddenByAdminFalseOrderByCreatedAtDesc(recipient)
        .stream()
        .filter(r -> r.getRoom() != null)
        .map(ReviewDto::from)
        .toList();
  }

  @Transactional(readOnly = true)
  public ReputationDto getReputation(Long userId) {
    var user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    // B5: rating only aggregates reviews anchored to a real shared room.
    var reviews =
        reviewRepository.findByRecipientAndHiddenByAdminFalseOrderByCreatedAtDesc(user).stream()
            .filter(r -> r.getRoom() != null)
            .toList();
    double avg =
        reviews.isEmpty()
            ? User.DEFAULT_REPUTATION / 10.0
            : reviews.stream().mapToInt(Review::getRating).average().orElse(0.0);
    return ReputationDto.builder()
        .userId(user.getId())
        .displayName(user.getDisplayName())
        .avatar(avatarStorageService.publicUrl(user.getAvatar()))
        .reputation(user.getReputation())
        .reputationLevel(reputationService.levelOf(user.getReputation()).name())
        .averageRating(Math.round(avg * 10.0) / 10.0)
        .reviewsCount((long) reviews.size())
        .completedRoomsCount(reputationService.completedRoomsCount(user))
        .build();
  }
}
