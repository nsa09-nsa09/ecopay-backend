package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.dto.CreateRoomRequest;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.dto.RegisterRequest;
import kz.hrms.splitupauth.dto.RoomSummaryDto;
import kz.hrms.splitupauth.entity.RoomType;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.repository.UserRepository;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Block 3 regression guard: the public room list ({@code RoomService.getRooms}) must issue a
 * CONSTANT number of SQL statements per page, independent of how many rows the page holds. Before
 * the {@code @EntityGraph(owner, service)} fix, each row triggered two extra lazy SELECTs (N+1), so
 * query count grew with row count.
 *
 * <p>The proof is by comparison: measure the statement count for a page over N distinct-owner rooms,
 * then again after adding more distinct-owner rooms. Distinct owners/services defeat the persistence
 * context cache that would otherwise mask N+1. If the two counts are equal, no per-row query exists.
 */
class RoomListQueryCountIntegrationTest extends AbstractIntegrationTest {

  @Autowired AuthService authService;
  @Autowired PhoneVerificationService phoneVerificationService;
  @Autowired RoomService roomService;
  @Autowired UserRepository userRepository;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired EntityManagerFactory entityManagerFactory;

  private static final AtomicInteger SEQ = new AtomicInteger();

  @BeforeEach
  void allowLegacyFourSeatTariff() {
    jdbcTemplate.update("UPDATE room_settings SET minimum_room_members = 4 WHERE id = 1");
  }

  @AfterEach
  void restoreRoomMinimum() {
    jdbcTemplate.update("UPDATE room_settings SET minimum_room_members = 5 WHERE id = 1");
  }

  @Test
  void roomListPage_issuesConstantQueryCount_regardlessOfRowCount() {
    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);

    // Batch 1: a few rooms, each owned by a distinct freshly-registered owner.
    createRoomsWithDistinctOwners(3);
    long firstBatchQueries = measurePageQueries(stats);

    // Batch 2: add more distinct-owner rooms, so the page now carries strictly more rows.
    createRoomsWithDistinctOwners(4);
    long secondBatchQueries = measurePageQueries(stats);

    assertEquals(
        firstBatchQueries,
        secondBatchQueries,
        "room list page query count must not grow with the number of rows (N+1 regression)");
    // Sanity: a page is a small constant number of statements (content + count + batched enrich),
    // nowhere near 2 queries per row.
    assertTrue(
        secondBatchQueries < 12,
        "expected a small constant query count, got " + secondBatchQueries);
  }

  private long measurePageQueries(Statistics stats) {
    stats.clear();
    PagedResponse<RoomSummaryDto> page = roomService.getRooms(0, 20, null, "newest", "desc");
    // Touch the lazy-prone fields the mapper reads, to be sure they were fetched eagerly and don't
    // lazy-load during this assertion (which would itself be an N+1 symptom).
    for (RoomSummaryDto dto : page.getItems()) {
      assertTrue(dto.getOwnerDisplayName() != null || dto.getOwnerUserId() != null);
      assertTrue(dto.getServiceId() != null);
    }
    return stats.getPrepareStatementCount();
  }

  private void createRoomsWithDistinctOwners(int count) {
    for (int i = 0; i < count; i++) {
      User owner = registerVerified("QC Owner");
      givePayoutCard(owner);
      CreateRoomRequest create = new CreateRoomRequest();
      create.setServiceId(2L); // seeded Netflix (DIGITAL, EMAIL access)
      create.setTariffPlanId(2L);
      create.setCategoryId(1L);
      create.setRoomType(RoomType.DIGITAL);
      create.setTitle("QC Room " + SEQ.get());
      create.setStartDate(LocalDateTime.now().plusMonths(2));
      roomService.createRoom(owner, create);
    }
  }

  private User registerVerified(String name) {
    int n = SEQ.incrementAndGet();
    RegisterRequest req = new RegisterRequest();
    req.setEmail("qc_" + n + "_" + System.nanoTime() + "@test.kz");
    req.setPassword("Test1234");
    req.setDisplayName(name);
    authService.register(req, MailLocale.RU, null);
    User user = userRepository.findByEmail(req.getEmail()).orElseThrow();
    String phone = "+77" + String.format("%09d", (System.nanoTime() % 1_000_000_000L));
    phoneVerificationService.requestCode(user, phone, null);
    phoneVerificationService.verifyCode(user, phone, "000000");
    return userRepository.findByEmail(req.getEmail()).orElseThrow();
  }

  private void givePayoutCard(User owner) {
    jdbcTemplate.update(
        "INSERT INTO payout_methods (user_id, provider_name, provider_card_token, pan_mask, "
            + "is_default, status, created_at) VALUES (?, 'freedompay', ?, '4242', TRUE, "
            + "'ACTIVE', CURRENT_TIMESTAMP)",
        owner.getId(),
        "tok_test_" + owner.getId());
  }
}
