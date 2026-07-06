package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import kz.hrms.splitupauth.dto.MemberDashboardDto;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.PeriodType;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.repository.DisputeRepository;
import kz.hrms.splitupauth.repository.ReviewRepository;
import kz.hrms.splitupauth.repository.RoomEventLogRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class MemberDashboardServiceTest {

  @Mock private EntityManager em;
  @Mock private RoomMemberRepository roomMemberRepository;
  @Mock private ReviewRepository reviewRepository;
  @Mock private DisputeRepository disputeRepository;
  @Mock private RoomEventLogRepository roomEventLogRepository;
  @Mock private TypedQuery<BigDecimal> bigDecimalQuery;

  private MemberDashboardService service;

  @BeforeEach
  void setUp() {
    service =
        new MemberDashboardService(
            em, roomMemberRepository, reviewRepository, disputeRepository, roomEventLogRepository);
    lenient().when(em.createQuery(anyString(), eq(BigDecimal.class))).thenReturn(bigDecimalQuery);
    lenient().when(bigDecimalQuery.setParameter(anyInt(), any())).thenReturn(bigDecimalQuery);
    lenient().when(bigDecimalQuery.getSingleResult()).thenReturn(new BigDecimal("12345.67"));
  }

  @Test
  void aggregatesMembershipRollupsAndKztAmounts() {
    User user = user(1L);
    Room activeRoom =
        roomKzt(
            100L,
            RoomStatus.ACTIVE,
            PeriodType.MONTHLY,
            new BigDecimal("5000"),
            new BigDecimal("3000"));
    Room completedRoom =
        roomKzt(
            101L,
            RoomStatus.COMPLETED,
            PeriodType.YEARLY,
            new BigDecimal("60000"),
            new BigDecimal("20000"));
    RoomMember activeMember = member(user, activeRoom, MemberStatus.ACTIVE);
    RoomMember completedMember = member(user, completedRoom, MemberStatus.ACTIVE);

    when(roomMemberRepository.findByUserAndDeletedAtIsNullOrderByCreatedAtDesc(user))
        .thenReturn(List.of(activeMember, completedMember));
    when(reviewRepository.countByRecipientAndHiddenByAdminFalse(user)).thenReturn(2L);
    when(disputeRepository.countByOpenedByUser(user)).thenReturn(1L);
    when(roomEventLogRepository.findByActorUserOrderByCreatedAtDesc(eq(user), any(Pageable.class)))
        .thenReturn(Collections.emptyList());

    MemberDashboardDto dto = service.getMyDashboard(user);

    assertEquals(2, dto.getTotalRoomsJoined());
    assertEquals(2, dto.getJoinedRoomsActive(), "both rooms are ACTIVE memberships");
    assertEquals(1, dto.getJoinedRoomsCompleted(), "completed room state cascades to dto");
    // Monthly spend = sum of pricePerMemberKzt across ACTIVE memberships.
    assertEquals(0, new BigDecimal("23000.00").compareTo(dto.getMonthlySpendKzt()));
    // totalSavedKzt = (5000-3000) + (60000-20000) = 42_000.
    assertEquals(0, new BigDecimal("42000.00").compareTo(dto.getTotalSavedKzt()));
    // totalSpentKzt is provided by the EntityManager mock (12345.67) → rounded.
    assertEquals(0, new BigDecimal("12345.67").compareTo(dto.getTotalSpentKzt()));
    assertEquals(2L, dto.getReviewsReceived());
    assertEquals(1L, dto.getDisputesAsMember());
    assertNotNull(
        dto.getNextPaymentDate(), "next billing should project from MONTHLY/YEARLY cycle");
    assertTrue(
        dto.getNextPaymentDate().isAfter(LocalDateTime.now().minusSeconds(1)),
        "next payment must roll forward into the future");
    assertNotNull(dto.getRecentEvents());
  }

  @Test
  void emptyMembershipReturnsZeroes() {
    User user = user(2L);
    when(roomMemberRepository.findByUserAndDeletedAtIsNullOrderByCreatedAtDesc(user))
        .thenReturn(Collections.emptyList());
    when(reviewRepository.countByRecipientAndHiddenByAdminFalse(user)).thenReturn(0L);
    when(disputeRepository.countByOpenedByUser(user)).thenReturn(0L);
    when(roomEventLogRepository.findByActorUserOrderByCreatedAtDesc(eq(user), any(Pageable.class)))
        .thenReturn(Collections.emptyList());

    MemberDashboardDto dto = service.getMyDashboard(user);

    assertEquals(0, dto.getTotalRoomsJoined());
    assertEquals(0, dto.getJoinedRoomsActive());
    assertEquals(0, BigDecimal.ZERO.compareTo(dto.getMonthlySpendKzt()));
    assertEquals(0, BigDecimal.ZERO.compareTo(dto.getTotalSavedKzt()));
  }

  // ----- fixtures -----

  private User user(Long id) {
    User u = new User();
    u.setId(id);
    u.setReputation(80);
    return u;
  }

  private Room roomKzt(
      Long id,
      RoomStatus status,
      PeriodType period,
      BigDecimal priceTotalKzt,
      BigDecimal pricePerMemberKzt) {
    Room r = new Room();
    r.setId(id);
    r.setStatus(status);
    r.setPeriodType(period);
    r.setCurrency("KZT");
    r.setFxRateToKzt(BigDecimal.ONE);
    r.setPriceTotal(priceTotalKzt);
    r.setPricePerMember(pricePerMemberKzt);
    r.setPriceTotalKzt(priceTotalKzt);
    r.setPricePerMemberKzt(pricePerMemberKzt);
    // Past start so the next-payment projection rolls forward into the future.
    r.setStartDate(LocalDateTime.now().minusMonths(1).minusDays(1));
    return r;
  }

  private RoomMember member(User user, Room room, MemberStatus status) {
    RoomMember m = new RoomMember();
    m.setUser(user);
    m.setRoom(room);
    m.setStatus(status);
    m.setCreatedAt(LocalDateTime.now().minusDays(30));
    return m;
  }
}
