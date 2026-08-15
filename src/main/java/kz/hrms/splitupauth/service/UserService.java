package kz.hrms.splitupauth.service;

import java.time.LocalDateTime;
import java.util.List;
import kz.hrms.splitupauth.dto.PublicProfileDto;
import kz.hrms.splitupauth.dto.SlugAvailabilityDto;
import kz.hrms.splitupauth.dto.UpdateProfileRequest;
import kz.hrms.splitupauth.dto.UserDto;
import kz.hrms.splitupauth.entity.Review;
import kz.hrms.splitupauth.entity.DisputeStatus;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import kz.hrms.splitupauth.entity.RefundStatus;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.ResourceConflictException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.DisputeRepository;
import kz.hrms.splitupauth.repository.PaymentIntentRepository;
import kz.hrms.splitupauth.repository.PayoutRepository;
import kz.hrms.splitupauth.repository.RefundTransactionRepository;
import kz.hrms.splitupauth.repository.ReviewRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.ServiceReviewRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.util.SlugGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class UserService {

  private final UserRepository userRepository;
  private final UserMapper userMapper;
  private final ReviewRepository reviewRepository;
  private final ServiceReviewRepository serviceReviewRepository;
  private final TokenRevocationService tokenRevocationService;
  private final AvatarStorageService avatarStorageService;
  private final ReputationService reputationService;
  private final SlugService slugService;
  private final RoomRepository roomRepository;
  private final RoomMemberRepository roomMemberRepository;
  private final PaymentIntentRepository paymentIntentRepository;
  private final RefundTransactionRepository refundTransactionRepository;
  private final PayoutRepository payoutRepository;
  private final DisputeRepository disputeRepository;

  @Transactional(readOnly = true)
  public UserDto getCurrentUser(User user) {
    return userMapper.toDto(user);
  }

  @Transactional
  public UserDto updateProfile(User user, UpdateProfileRequest request) {
    // Avatars are managed exclusively through /me/avatar (S3 upload) and
    // /me/avatar DELETE — the profile PATCH only carries the display name.
    // Setting an arbitrary public URL is no longer supported.
    user.setDisplayName(request.getDisplayName());

    String requestedSlug = request.getSlug();
    if (requestedSlug != null && !requestedSlug.isBlank()) {
      String trimmed = requestedSlug.trim();
      if (!trimmed.equals(user.getSlug())) {
        slugService.changeSlug(user, trimmed);
      }
    }

    userRepository.save(user);

    return userMapper.toDto(user);
  }

  @Transactional(readOnly = true)
  public SlugAvailabilityDto checkSlugAvailability(User user, String slug) {
    String normalized = SlugGenerator.normalize(slug);
    if (!normalized.matches("^[a-z0-9]([a-z0-9-]{1,28}[a-z0-9])$")) {
      return new SlugAvailabilityDto(false, normalized, "invalid");
    }
    if (SlugService.RESERVED.contains(normalized)) {
      return new SlugAvailabilityDto(false, normalized, "reserved");
    }
    if (slugService.isTaken(normalized, user.getId())) {
      return new SlugAvailabilityDto(false, normalized, "taken");
    }
    return new SlugAvailabilityDto(true, normalized, null);
  }

  @Transactional
  public UserDto uploadAvatar(User user, MultipartFile file) {
    String url = avatarStorageService.store(file);
    avatarStorageService.deleteIfManaged(user.getAvatar());
    user.setAvatar(url);
    userRepository.save(user);
    return userMapper.toDto(user);
  }

  @Transactional
  public UserDto deleteAvatar(User user) {
    avatarStorageService.deleteIfManaged(user.getAvatar());
    user.setAvatar(null);
    userRepository.save(user);
    return userMapper.toDto(user);
  }

  @Transactional(readOnly = true)
  public PublicProfileDto getPublicProfile(String handle) {
    // Slug first (the readable handle), fall back to the immutable publicId so old
    // links keep working after a user renames themselves.
    User user =
        userRepository
            .findBySlug(handle)
            .or(() -> userRepository.findByPublicId(handle))
            .filter(u -> u.getStatus() != UserStatus.DELETED)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));

    List<Review> reviews =
        reviewRepository.findByRecipientAndHiddenByAdminFalseOrderByCreatedAtDesc(user);
    // No reviews yet → the neutral starting rating (5.0/10), never null/0.
    double avg =
        reviews.isEmpty()
            ? User.DEFAULT_REPUTATION / 10.0
            : reviews.stream().mapToInt(Review::getRating).average().orElse(0.0);

    return PublicProfileDto.builder()
        .id(user.getId())
        .publicId(user.getPublicId())
        .slug(user.getSlug())
        .displayName(user.getDisplayName())
        .avatar(avatarStorageService.publicUrl(user.getAvatar()))
        .reputation(user.getReputation())
        .reputationLevel(reputationService.levelOf(user.getReputation()).name())
        .status(user.getStatus())
        .averageRating(Math.round(avg * 10.0) / 10.0)
        .reviewsCount((long) reviews.size())
        .completedRoomsCount(reputationService.completedRoomsCount(user))
        .createdAt(user.getCreatedAt())
        .build();
  }

  /**
   * Soft-deletes the current user. Per CLAUDE.md: anonymize PII, retain financial/audit events.
   * Refresh tokens are revoked so the session can't be used after deletion. Any testimonial the
   * user wrote is removed (the homepage carousel would otherwise show a "Удалённый пользователь"
   * entry).
   */
  @Transactional
  public void deleteAccount(User user) {
    ensureNoDeletionBlockers(user);

    // Remove their service-review (testimonial) so the carousel doesn't
    // display anonymized data.
    serviceReviewRepository.findByAuthor(user).ifPresent(serviceReviewRepository::delete);

    // Free disk for any uploaded avatar before the row is anonymized.
    avatarStorageService.deleteIfManaged(user.getAvatar());

    Long id = user.getId();
    user.setStatus(UserStatus.DELETED);
    user.setDeletedAt(LocalDateTime.now());
    user.setEmail("deleted-" + id + "@ecopay.local");
    user.setDisplayName("Удалённый пользователь");
    user.setPhone(null);
    user.setPhoneVerifiedAt(null);
    user.setAvatar(null);
    userRepository.save(user);

    tokenRevocationService.revokeAllUserTokens(user);
  }

  private void ensureNoDeletionBlockers(User user) {
    if (roomRepository.countByOwnerAndDeletedAtIsNullAndStatusIn(
            user, List.of(RoomStatus.OPEN, RoomStatus.IN_VERIFICATION, RoomStatus.ACTIVE))
        > 0) {
      throw deletionConflict("owned active/open room");
    }
    if (roomMemberRepository.countByUserAndDeletedAtIsNullAndStatusIn(
            user, List.of(MemberStatus.APPLIED, MemberStatus.PENDING, MemberStatus.ACTIVE))
        > 0) {
      throw deletionConflict("active or pending membership");
    }
    if (paymentIntentRepository.countByUserAndStatusIn(
            user,
            List.of(
                PaymentIntentStatus.PENDING,
                PaymentIntentStatus.UNKNOWN,
                PaymentIntentStatus.RECONCILING,
                PaymentIntentStatus.REFUND_REQUIRED,
                PaymentIntentStatus.REFUND_PENDING,
                PaymentIntentStatus.REQUIRES_REVIEW,
                PaymentIntentStatus.CAPTURE_ANOMALY))
        > 0) {
      throw deletionConflict("pending or review payment");
    }
    if (refundTransactionRepository.countByPaymentTransaction_PaymentIntent_UserAndStatusIn(
            user, List.of(RefundStatus.PENDING, RefundStatus.FAILED, RefundStatus.REQUIRES_REVIEW))
        > 0) {
      throw deletionConflict("pending refund");
    }
    if (payoutRepository.countByUserAndStatusIn(
            user, List.of("PENDING", "PENDING_METHOD", "PROCESSING", "ON_HOLD", "CLAWBACK_REQUIRED"))
        > 0) {
      throw deletionConflict("pending payout");
    }
    if (disputeRepository.countOpenFinancialDisputesForUser(
            user, List.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW))
        > 0) {
      throw deletionConflict("open dispute");
    }
  }

  private ResourceConflictException deletionConflict(String reason) {
    return new ResourceConflictException(
        "ACCOUNT_DELETION_BLOCKED", "Account has unresolved obligations: " + reason);
  }
}
