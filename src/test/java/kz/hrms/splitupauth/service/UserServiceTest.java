package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.dto.PublicProfileDto;
import kz.hrms.splitupauth.entity.DeletedUserIdentityArchive;
import kz.hrms.splitupauth.entity.ReputationLevel;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.ServiceReview;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.DeletedUserIdentityArchiveRepository;
import kz.hrms.splitupauth.repository.DisputeRepository;
import kz.hrms.splitupauth.repository.PaymentIntentRepository;
import kz.hrms.splitupauth.repository.PayoutRepository;
import kz.hrms.splitupauth.repository.RefundTransactionRepository;
import kz.hrms.splitupauth.repository.ReviewRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.ServiceReviewRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.security.FieldEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

  @Mock private UserRepository userRepository;
  @Mock private ReviewRepository reviewRepository;
  @Mock private ServiceReviewRepository serviceReviewRepository;
  @Mock private TokenRevocationService tokenRevocationService;
  @Mock private AvatarStorageService avatarStorageService;
  @Mock private ReputationService reputationService;
  @Mock private SlugService slugService;
  @Mock private RoomRepository roomRepository;
  @Mock private RoomMemberRepository roomMemberRepository;
  @Mock private PaymentIntentRepository paymentIntentRepository;
  @Mock private RefundTransactionRepository refundTransactionRepository;
  @Mock private PayoutRepository payoutRepository;
  @Mock private DisputeRepository disputeRepository;
  @Mock private DeletedUserIdentityArchiveRepository identityArchiveRepository;
  @Mock private FieldEncryptionService fieldEncryptionService;

  private UserService service;

  @BeforeEach
  void setUp() {
    service =
        new UserService(
            userRepository,
            new UserMapper(avatarStorageService),
            reviewRepository,
            serviceReviewRepository,
            tokenRevocationService,
            avatarStorageService,
            reputationService,
            slugService,
            roomRepository,
            roomMemberRepository,
            paymentIntentRepository,
            refundTransactionRepository,
            payoutRepository,
            disputeRepository,
            identityArchiveRepository,
            fieldEncryptionService);
    // Real impl never returns null; the mock would, so give it a sane default.
    lenient().when(reputationService.levelOf(any())).thenReturn(ReputationLevel.EXCELLENT);
    lenient().when(reputationService.completedRoomsCount(any())).thenReturn(0L);
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
        .thenReturn(
            List.of(
                kz.hrms.splitupauth.entity.Review.builder().rating(5).build(),
                kz.hrms.splitupauth.entity.Review.builder().rating(4).build()));

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

    assertThrows(ResourceNotFoundException.class, () -> service.getPublicProfile("publ11"));
  }

  @Test
  void getPublicProfile_404WhenMissing() {
    when(userRepository.findByPublicId("nope")).thenReturn(Optional.empty());
    assertThrows(ResourceNotFoundException.class, () -> service.getPublicProfile("nope"));
  }

  @Test
  void deleteAccount_anonymizesPII_revokesTokens_andRemovesTestimonial() {
    User u = activeUser(42L);
    when(userRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(u));
    ServiceReview testimonial =
        ServiceReview.builder().id(1L).author(u).text("x").rating(5).build();
    when(serviceReviewRepository.findByAuthor(u)).thenReturn(Optional.of(testimonial));
    when(fieldEncryptionService.encrypt("u42@e.kz")).thenReturn("encrypted-email");
    when(fieldEncryptionService.encrypt("+77001234567")).thenReturn("encrypted-phone");

    service.deleteAccount(u);

    verify(serviceReviewRepository).delete(testimonial);
    ArgumentCaptor<DeletedUserIdentityArchive> archiveCap =
        ArgumentCaptor.forClass(DeletedUserIdentityArchive.class);
    verify(identityArchiveRepository).save(archiveCap.capture());
    assertEquals("encrypted-email", archiveCap.getValue().getEmailEncrypted());
    assertEquals("encrypted-phone", archiveCap.getValue().getPhoneEncrypted());
    assertEquals("u*2@e.kz", archiveCap.getValue().getEmailMasked());
    assertEquals("+7700*****67", archiveCap.getValue().getPhoneMasked());
    assertEquals("Айдар К.", archiveCap.getValue().getDisplayNameAtDeletion());

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
    when(userRepository.findByIdForUpdate(43L)).thenReturn(Optional.of(u));
    when(serviceReviewRepository.findByAuthor(u)).thenReturn(Optional.empty());

    service.deleteAccount(u);

    verify(userRepository).save(any(User.class));
    verify(tokenRevocationService).revokeAllUserTokens(u);
  }

  @Test
  void deleteAccount_keepsBlockersBeforeArchive() {
    User u = activeUser(44L);
    when(userRepository.findByIdForUpdate(44L)).thenReturn(Optional.of(u));
    when(roomRepository.countByOwnerAndDeletedAtIsNullAndStatusIn(any(), any())).thenReturn(1L);
    assertThrows(
        kz.hrms.splitupauth.exception.ResourceConflictException.class,
        () -> service.deleteAccount(u));
    verify(identityArchiveRepository, never()).save(any());
    verify(tokenRevocationService, never()).revokeAllUserTokens(any());
  }

  @Test
  void archivePhoneMask_preservesPrefixAndLastTwo() {
    assertEquals("+7705*****65", UserService.maskArchivedPhone("+77051234565"));
  }
}
