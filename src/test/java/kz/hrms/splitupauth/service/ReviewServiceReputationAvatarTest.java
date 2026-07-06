package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.dto.ReputationDto;
import kz.hrms.splitupauth.entity.ReputationLevel;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.repository.ReviewRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Guards the public-profile avatar wiring: {@link ReviewService#getReputation(Long)} must surface
 * the avatar URL the same way the own-profile endpoint does, via {@link
 * AvatarStorageService#publicUrl(String)}. Storage and review aggregation are mocked — this test
 * only cares about the DTO assembly.
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceReputationAvatarTest {

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

  @Test
  void getReputation_withStoredAvatarKey_returnsPublicUrl() {
    User user =
        User.builder().id(42L).displayName("Asel").reputation(50).avatar("avatars/abc.jpg").build();
    when(userRepository.findById(42L)).thenReturn(Optional.of(user));
    when(reviewRepository.findByRecipientAndHiddenByAdminFalseOrderByCreatedAtDesc(user))
        .thenReturn(List.of());
    lenient().when(reputationService.levelOf(any())).thenReturn(ReputationLevel.NEWCOMER);
    lenient().when(reputationService.completedRoomsCount(user)).thenReturn(0L);
    when(avatarStorageService.publicUrl("avatars/abc.jpg"))
        .thenReturn("https://api.example.com/api/v1/users/avatars/abc.jpg");

    ReputationDto dto = service().getReputation(42L);

    assertEquals("https://api.example.com/api/v1/users/avatars/abc.jpg", dto.getAvatar());
    assertEquals("Asel", dto.getDisplayName());
  }

  @Test
  void getReputation_withoutAvatar_returnsNull() {
    User user = User.builder().id(7L).displayName("No-pic").reputation(0).avatar(null).build();
    when(userRepository.findById(7L)).thenReturn(Optional.of(user));
    when(reviewRepository.findByRecipientAndHiddenByAdminFalseOrderByCreatedAtDesc(user))
        .thenReturn(List.of());
    lenient().when(reputationService.levelOf(any())).thenReturn(ReputationLevel.NEWCOMER);
    lenient().when(reputationService.completedRoomsCount(user)).thenReturn(0L);
    // Storage returns null for a null key — same as the production publicUrl contract.
    when(avatarStorageService.publicUrl(null)).thenReturn(null);

    ReputationDto dto = service().getReputation(7L);

    assertNull(dto.getAvatar());
  }
}
