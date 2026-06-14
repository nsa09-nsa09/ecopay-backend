package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.PublicProfileDto;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.ServiceReview;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.ReviewRepository;
import kz.hrms.splitupauth.repository.ServiceReviewRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private ReviewRepository reviewRepository;
    @Mock private ServiceReviewRepository serviceReviewRepository;
    @Mock private TokenRevocationService tokenRevocationService;
    @Mock private AvatarStorageService avatarStorageService;

    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(
                userRepository, new UserMapper(avatarStorageService),
                reviewRepository, serviceReviewRepository, tokenRevocationService,
                avatarStorageService);
    }

    private User activeUser(long id) {
        return User.builder()
                .id(id)
                .email("u" + id + "@e.kz")
                .displayName("Айдар К.")
                .phone("+77001234567")
                .publicId("publ" + id)
                .role(Role.USER)
                .reputation(80)
                .emailVerified(true)
                .status(UserStatus.ACTIVE)
                .build();
    }

    @Test
    void getPublicProfile_omitsPII_andReturnsAverageRating() {
        User u = activeUser(10L);
        when(userRepository.findByPublicId("publ10")).thenReturn(Optional.of(u));
        when(reviewRepository.findByRecipientAndHiddenByAdminFalseOrderByCreatedAtDesc(u))
                .thenReturn(List.of(
                        kz.hrms.splitupauth.entity.Review.builder().rating(5).build(),
                        kz.hrms.splitupauth.entity.Review.builder().rating(4).build()
                ));

        PublicProfileDto dto = service.getPublicProfile("publ10");

        assertEquals(10L, dto.getId());
        assertEquals("publ10", dto.getPublicId());
        assertEquals("Айдар К.", dto.getDisplayName());
        assertEquals(4.5, dto.getAverageRating());
        assertEquals(2L, dto.getReviewsCount());
        assertEquals(UserStatus.ACTIVE, dto.getStatus());
    }

    @Test
    void getPublicProfile_404WhenDeleted() {
        User u = activeUser(11L);
        u.setStatus(UserStatus.DELETED);
        when(userRepository.findByPublicId("publ11")).thenReturn(Optional.of(u));

        assertThrows(ResourceNotFoundException.class,
                () -> service.getPublicProfile("publ11"));
    }

    @Test
    void getPublicProfile_404WhenMissing() {
        when(userRepository.findByPublicId("nope")).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> service.getPublicProfile("nope"));
    }

    @Test
    void deleteAccount_anonymizesPII_revokesTokens_andRemovesTestimonial() {
        User u = activeUser(42L);
        ServiceReview testimonial = ServiceReview.builder().id(1L).author(u).text("x").rating(5).build();
        when(serviceReviewRepository.findByAuthor(u)).thenReturn(Optional.of(testimonial));

        service.deleteAccount(u);

        verify(serviceReviewRepository).delete(testimonial);

        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(cap.capture());
        User saved = cap.getValue();
        assertEquals(UserStatus.DELETED, saved.getStatus());
        assertNotNull(saved.getDeletedAt());
        assertEquals("deleted-42@ecopay.local", saved.getEmail());
        assertEquals("Удалённый пользователь", saved.getDisplayName());
        assertNull(saved.getPhone());
        assertNull(saved.getAvatar());

        verify(tokenRevocationService).revokeAllUserTokens(u);
    }

    @Test
    void deleteAccount_succeedsEvenWithoutTestimonial() {
        User u = activeUser(43L);
        when(serviceReviewRepository.findByAuthor(u)).thenReturn(Optional.empty());

        service.deleteAccount(u);

        verify(userRepository).save(any(User.class));
        verify(tokenRevocationService).revokeAllUserTokens(u);
    }
}
