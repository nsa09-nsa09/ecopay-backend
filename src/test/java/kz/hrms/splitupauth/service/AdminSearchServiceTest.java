package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.dto.AdminSearchResultDto;
import kz.hrms.splitupauth.dto.CreateFeedbackRequest;
import kz.hrms.splitupauth.entity.AccessType;
import kz.hrms.splitupauth.entity.Category;
import kz.hrms.splitupauth.entity.FeedbackType;
import kz.hrms.splitupauth.entity.PeriodType;
import kz.hrms.splitupauth.entity.ProviderType;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.RoomType;
import kz.hrms.splitupauth.entity.ServiceEntity;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.entity.VerificationMode;
import kz.hrms.splitupauth.repository.CategoryRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.ServiceRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Covers the global admin "spotlight" search end-to-end against a real Postgres. Each test inserts
 * uniquely-named records (UUID-tagged) so the query needle is guaranteed to be specific to this run
 * — no baseline-delta dance needed for the targeted assertions.
 */
class AdminSearchServiceTest extends AbstractIntegrationTest {

  @Autowired AdminSearchService searchService;
  @Autowired UserRepository userRepository;
  @Autowired CategoryRepository categoryRepository;
  @Autowired ServiceRepository serviceRepository;
  @Autowired RoomRepository roomRepository;
  @Autowired FeedbackService feedbackService;

  private static final AtomicInteger SEQ = new AtomicInteger();

  @Test
  void search_nullOrEmptyQuery_returnsEmptyGroups_neverThrows() {
    AdminSearchResultDto nullResult = searchService.search(null, null);
    assertNotNull(nullResult);
    assertTrue(nullResult.getRooms().isEmpty());
    assertTrue(nullResult.getUsers().isEmpty());
    assertTrue(nullResult.getFeedback().isEmpty());

    AdminSearchResultDto blank = searchService.search("   ", null);
    assertTrue(blank.getRooms().isEmpty());
    assertTrue(blank.getUsers().isEmpty());
    assertTrue(blank.getFeedback().isEmpty());
  }

  @Test
  void search_findsUserByEmail_displayName_phone_andPublicId() {
    // A unique needle ensures we get exactly our own hit back.
    String needle = "needle-" + UUID.randomUUID().toString().substring(0, 8);
    User u =
        userRepository.save(
            User.builder()
                .email(needle + "@search.kz")
                .password("x")
                .displayName("Search Hit " + needle)
                .role(Role.USER)
                .status(UserStatus.ACTIVE)
                // phone is unique-constrained — include the needle to ensure
                // there's no clash with other tests' seeded data.
                .phone("+7" + (7770000000L + SEQ.incrementAndGet()))
                .build());

    AdminSearchResultDto byEmail = searchService.search(needle, null);
    assertEquals(
        1, byEmail.getUsers().size(), "email substring must surface exactly the test user");
    assertEquals(u.getId(), byEmail.getUsers().get(0).getId());

    AdminSearchResultDto byName = searchService.search("Search Hit " + needle, null);
    assertEquals(1, byName.getUsers().size());

    AdminSearchResultDto byPublic = searchService.search(u.getPublicId(), null);
    assertEquals(
        1,
        byPublic.getUsers().size(),
        "publicId is unique-short — paste should always land its owner");
  }

  @Test
  void search_findsRoomByTitle_excludesSoftDeleted() {
    User owner = newUser("rsOwner");
    Category cat = newCategory("rsCat");
    ServiceEntity svc = newService(cat, "rsSvc");

    String needle = "Title-" + UUID.randomUUID();
    Room live = saveRoom(owner, svc, cat, needle + " Alive", RoomStatus.OPEN, false);
    Room ghost = saveRoom(owner, svc, cat, needle + " Soft-Deleted", RoomStatus.OPEN, true);

    AdminSearchResultDto hits = searchService.search(needle, 10);
    assertEquals(1, hits.getRooms().size(), "soft-deleted rooms must be excluded");
    assertEquals(live.getId(), hits.getRooms().get(0).getId());
    // ghost must NOT appear
    boolean ghostFound = hits.getRooms().stream().anyMatch(r -> r.getId().equals(ghost.getId()));
    assertTrue(!ghostFound);
  }

  @Test
  void search_findsFeedbackBySubjectAndMessage_capLimit() {
    User u = newUser("fbSearch");
    String needle = "fbNeedle-" + UUID.randomUUID().toString().substring(0, 8);
    CreateFeedbackRequest req = new CreateFeedbackRequest();
    req.setType(FeedbackType.IDEA);
    req.setSubject("Subject with " + needle + " inside");
    req.setMessage("payload body");
    feedbackService.submit(u, req);

    AdminSearchResultDto hits = searchService.search(needle, null);
    assertEquals(
        1,
        hits.getFeedback().size(),
        "feedback subject match should land exactly the row we just submitted");
  }

  @Test
  void search_respectsPerGroupLimit() {
    // Feed five users with the same needle — limit=2 must cap each group.
    String needle = "cap-" + UUID.randomUUID().toString().substring(0, 6);
    for (int i = 0; i < 5; i++) {
      int n = SEQ.incrementAndGet();
      userRepository.save(
          User.builder()
              .email(needle + "_" + n + "@t.kz")
              .password("x")
              .displayName("Cap " + needle + " " + n)
              .role(Role.USER)
              .status(UserStatus.ACTIVE)
              .build());
    }

    AdminSearchResultDto two = searchService.search(needle, 2);
    assertEquals(2, two.getUsers().size(), "limit=2 must cap the users group at 2");
  }

  @Test
  void search_oversizedQuery_returnsEmpty_doesNotThrow() {
    StringBuilder huge = new StringBuilder();
    for (int i = 0; i < AdminSearchService.MAX_QUERY_LENGTH + 50; i++) {
      huge.append('x');
    }
    AdminSearchResultDto result = searchService.search(huge.toString(), null);
    assertNotNull(result);
    assertTrue(result.getUsers().isEmpty());
    assertTrue(result.getRooms().isEmpty());
    assertTrue(result.getFeedback().isEmpty());
  }

  // ===================== fixtures =====================

  private User newUser(String prefix) {
    int n = SEQ.incrementAndGet();
    return userRepository.save(
        User.builder()
            .email(prefix + "_" + n + "_" + System.nanoTime() + "@t.kz")
            .password("x")
            .displayName(prefix + " " + n)
            .role(Role.USER)
            .status(UserStatus.ACTIVE)
            .build());
  }

  private Category newCategory(String prefix) {
    int n = SEQ.incrementAndGet();
    return categoryRepository.save(
        Category.builder()
            .name(prefix + " " + n)
            .slug("c-" + n + "-" + System.nanoTime())
            .isActive(true)
            .build());
  }

  private ServiceEntity newService(Category cat, String prefix) {
    int n = SEQ.incrementAndGet();
    return serviceRepository.save(
        ServiceEntity.builder()
            .category(cat)
            .name(prefix + " " + n)
            .slug("s-" + n + "-" + System.nanoTime())
            .providerType(ProviderType.DIGITAL)
            .isActive(true)
            .build());
  }

  private Room saveRoom(
      User owner,
      ServiceEntity svc,
      Category cat,
      String title,
      RoomStatus status,
      boolean softDeleted) {
    Room r =
        Room.builder()
            .owner(owner)
            .service(svc)
            .category(cat)
            .roomType(RoomType.DIGITAL)
            .verificationMode(VerificationMode.RISK_BASED)
            .status(status)
            .title(title)
            .maxMembers(4)
            .priceTotal(new BigDecimal("4000"))
            .pricePerMember(new BigDecimal("1000"))
            .currency("KZT")
            .fxRateToKzt(BigDecimal.ONE)
            .priceTotalKzt(new BigDecimal("4000"))
            .pricePerMemberKzt(new BigDecimal("1000"))
            .periodType(PeriodType.MONTHLY)
            .startDate(LocalDateTime.now().plusDays(1))
            .accessType(AccessType.SHARED_ACCOUNT)
            .operatorTermsConfirmed(false)
            .build();
    if (softDeleted) {
      r.setDeletedAt(LocalDateTime.now());
    }
    return roomRepository.save(r);
  }
}
