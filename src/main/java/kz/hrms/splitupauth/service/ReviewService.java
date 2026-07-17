package kz.hrms.splitupauth.service;

import java.util.List;
import kz.hrms.splitupauth.dto.CreateReviewRequest;
import kz.hrms.splitupauth.dto.ReputationDto;
import kz.hrms.splitupauth.dto.ReviewDto;
import kz.hrms.splitupauth.entity.Review;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
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
  private final ReputationService reputationService;
  private final AvatarStorageService avatarStorageService;

  @Transactional
  public ReviewDto createReview(User author, CreateReviewRequest req) {
    if (author.getId().equals(req.getRecipientId())) {
      throw new InvalidRequestException("Cannot review yourself");
    }

    var recipient =
        userRepository
            .findById(req.getRecipientId())
            .orElseThrow(() -> new ResourceNotFoundException("Recipient not found"));

    Room room = null;
    if (req.getRoomId() != null) {
      room =
          roomRepository
              .findById(req.getRoomId())
              .orElseThrow(() -> new ResourceNotFoundException("Room not found"));
    }

    // Upsert on (author, recipient): a user has a single active rating for another user;
    // subsequent submissions update rating/text and optionally re-anchor the room.
    String sanitizedText = TextSanitizer.sanitize(req.getText());
    Review review =
        reviewRepository
            .findFirstByAuthorAndRecipientOrderByCreatedAtDesc(author, recipient)
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
      if (room != null) {
        review.setRoom(room);
      }
    }
    review = reviewRepository.save(review);

    reputationService.recompute(recipient);

    return ReviewDto.from(review);
  }

  @Transactional(readOnly = true)
  public List<ReviewDto> listForRecipient(Long userId) {
    var recipient =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    return reviewRepository
        .findByRecipientAndHiddenByAdminFalseOrderByCreatedAtDesc(recipient)
        .stream()
        .map(ReviewDto::from)
        .toList();
  }

  @Transactional(readOnly = true)
  public ReputationDto getReputation(Long userId) {
    var user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    var reviews = reviewRepository.findByRecipientAndHiddenByAdminFalseOrderByCreatedAtDesc(user);
    // No reviews yet → the neutral starting rating (5.0/10), never null/0.
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
