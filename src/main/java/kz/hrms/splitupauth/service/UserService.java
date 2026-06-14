package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.PublicProfileDto;
import kz.hrms.splitupauth.dto.UpdateProfileRequest;
import kz.hrms.splitupauth.dto.UserDto;
import kz.hrms.splitupauth.entity.Review;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.ReviewRepository;
import kz.hrms.splitupauth.repository.ServiceReviewRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final ReviewRepository reviewRepository;
    private final ServiceReviewRepository serviceReviewRepository;
    private final TokenRevocationService tokenRevocationService;

    @Transactional(readOnly = true)
    public UserDto getCurrentUser(User user) {
        return userMapper.toDto(user);
    }

    @Transactional
    public UserDto updateProfile(User user, UpdateProfileRequest request) {

        user.setDisplayName(request.getDisplayName());

        if (request.getAvatar() != null) {
            user.setAvatar(request.getAvatar());
        }

        userRepository.save(user);

        return userMapper.toDto(user);
    }

    @Transactional(readOnly = true)
    public PublicProfileDto getPublicProfile(String publicId) {
        User user = userRepository.findByPublicId(publicId)
                .filter(u -> u.getStatus() != UserStatus.DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        List<Review> reviews = reviewRepository
                .findByRecipientAndHiddenByAdminFalseOrderByCreatedAtDesc(user);
        double avg = reviews.isEmpty() ? 0.0
                : reviews.stream().mapToInt(Review::getRating).average().orElse(0.0);

        return PublicProfileDto.builder()
                .id(user.getId())
                .publicId(user.getPublicId())
                .displayName(user.getDisplayName())
                .avatar(user.getAvatar())
                .reputation(user.getReputation())
                .status(user.getStatus())
                .averageRating(Math.round(avg * 10.0) / 10.0)
                .reviewsCount((long) reviews.size())
                .completedRoomsCount(0L)
                .createdAt(user.getCreatedAt())
                .build();
    }

    /**
     * Soft-deletes the current user. Per CLAUDE.md: anonymize PII, retain
     * financial/audit events. Refresh tokens are revoked so the session can't
     * be used after deletion. Any testimonial the user wrote is removed (the
     * homepage carousel would otherwise show a "Удалённый пользователь" entry).
     */
    @Transactional
    public void deleteAccount(User user) {
        // Remove their service-review (testimonial) so the carousel doesn't
        // display anonymized data.
        serviceReviewRepository.findByAuthor(user).ifPresent(serviceReviewRepository::delete);

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
