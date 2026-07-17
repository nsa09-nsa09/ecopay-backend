package kz.hrms.splitupauth.service;

import java.time.LocalDateTime;
import java.util.List;
import kz.hrms.splitupauth.dto.PublicProfileDto;
import kz.hrms.splitupauth.dto.SlugAvailabilityDto;
import kz.hrms.splitupauth.dto.UpdateProfileRequest;
import kz.hrms.splitupauth.dto.UserDto;
import kz.hrms.splitupauth.entity.Review;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.ReviewRepository;
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
}
