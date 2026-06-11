package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.CreateReviewRequest;
import kz.hrms.splitupauth.util.TextSanitizer;
import kz.hrms.splitupauth.dto.ReputationDto;
import kz.hrms.splitupauth.dto.ReviewDto;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.Review;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.ReviewRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final UserRepository userRepository;
    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;

    @Transactional
    public ReviewDto createReview(User author, CreateReviewRequest req) {
        if (author.getId().equals(req.getRecipientId())) {
            throw new InvalidRequestException("Cannot review yourself");
        }

        var recipient = userRepository.findById(req.getRecipientId())
                .orElseThrow(() -> new ResourceNotFoundException("Recipient not found"));
        var room = roomRepository.findById(req.getRoomId())
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

        // Eligibility per spec: review allowed only after the period/participation ended.
        // ACTIVE means the period is still running — both sides are still co-using the
        // service and the experience isn't final yet, so reviews are not eligible.
        if (room.getStatus() != RoomStatus.COMPLETED) {
            throw new InvalidRequestException("Reviews are allowed only after the room is COMPLETED");
        }

        // Both author and recipient must have been members (or owner) of this room.
        boolean authorWasMember = author.getId().equals(room.getOwner().getId())
                || roomMemberRepository.findByRoomAndUserAndStatusIn(room, author,
                        List.of(MemberStatus.ACTIVE, MemberStatus.PENDING)).isPresent();
        boolean recipientWasMember = recipient.getId().equals(room.getOwner().getId())
                || roomMemberRepository.findByRoomAndUserAndStatusIn(room, recipient,
                        List.of(MemberStatus.ACTIVE, MemberStatus.PENDING)).isPresent();
        if (!authorWasMember || !recipientWasMember) {
            throw new ForbiddenOperationException("Both users must have shared this room");
        }

        if (reviewRepository.findByAuthorAndRecipientAndRoom_Id(
                author, recipient, req.getRoomId()).isPresent()) {
            throw new InvalidRequestException("You have already reviewed this user for this room");
        }

        Review review = Review.builder()
                .author(author)
                .recipient(recipient)
                .room(room)
                .rating(req.getRating())
                .text(TextSanitizer.sanitize(req.getText()))
                .build();
        review = reviewRepository.save(review);

        recalculateReputation(recipient);

        return ReviewDto.from(review);
    }

    @Transactional(readOnly = true)
    public List<ReviewDto> listForRecipient(Long userId) {
        var recipient = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return reviewRepository
                .findByRecipientAndHiddenByAdminFalseOrderByCreatedAtDesc(recipient)
                .stream().map(ReviewDto::from).toList();
    }

    @Transactional(readOnly = true)
    public ReputationDto getReputation(Long userId) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        var reviews = reviewRepository
                .findByRecipientAndHiddenByAdminFalseOrderByCreatedAtDesc(user);
        double avg = reviews.isEmpty() ? 0.0
                : reviews.stream().mapToInt(Review::getRating).average().orElse(0.0);
        return ReputationDto.builder()
                .userId(user.getId())
                .displayName(user.getDisplayName())
                .reputation(user.getReputation())
                .averageRating(Math.round(avg * 10.0) / 10.0)
                .reviewsCount((long) reviews.size())
                .completedRoomsCount(0L)
                .build();
    }

    private void recalculateReputation(User user) {
        var reviews = reviewRepository
                .findByRecipientAndHiddenByAdminFalseOrderByCreatedAtDesc(user);
        if (reviews.isEmpty()) return;
        double avg = reviews.stream().mapToInt(Review::getRating).average().orElse(0.0);
        // Simple formula: scale 1-5 → 0-100.
        int reputation = (int) Math.round(avg * 20);
        user.setReputation(reputation);
        userRepository.save(user);
    }
}
