package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import kz.hrms.splitupauth.repository.PaymentIntentRepository;
import kz.hrms.splitupauth.repository.ReviewRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberDashboardServiceTest {

  @Mock private EntityManager em;
  @Mock private RoomMemberRepository roomMemberRepository;
  @Mock private ReviewRepository reviewRepository;
  @Mock private DisputeRepository disputeRepository;
  @Mock private PaymentIntentRepository paymentIntentRepository;
  @Mock private PaymentService paymentService;
  @Mock private TypedQuery<BigDecimal> bigDecimalQuery;

  private MemberDashboardService service;

  @BeforeEach
  void setUp() {
    service =
        new MemberDashboardService(
            em,
            roomMemberRepository,
            reviewRepository,
            disputeRepository,
            paymentIntentRepository,
            paymentService);
    lenient().when(em.createQuery(anyString(), eq(BigDecimal.class))).thenReturn(bigDecimalQuery);
    lenient().when(bigDecimalQuery.setParameter(anyInt(), any())).thenReturn(bigDecimalQuery);
    lenient().when(bigDecimalQuery.getSingleResult()).thenReturn(new BigDecimal("12345.67"));
    lenient()
        .when(paymentService.currentChargeBreakdown(any()))
        .thenReturn(
            new PaymentService.ChargeBreakdown(
                new BigDecimal("3000.00"), new BigDecimal("500.00"), new BigDecimal("3500.00")));
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
    activeMember.setNextBillingAt(LocalDateTime.now().plusDays(10));
    RoomMember completedMember = member(user, completedRoom, MemberStatus.ACTIVE);
    completedMember.setNextBillingAt(LocalDateTime.now().plusMonths(6));

    when(roomMemberRepository.findByUserAndDeletedAtIsNullOrderByCreatedAtDesc(user))
        .thenReturn(List.of(activeMember, completedMember));
    when(reviewRepository.countByRecipientAndHiddenByAdminFalse(user)).thenReturn(2L);
    when(disputeRepository.countByOpenedByUser(user)).thenReturn(1L);
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
    assertNotNull(dto.getNextPaymentDate(), "next billing uses the member's nextBillingAt");
    assertEquals(
        activeMember.getNextBillingAt(),
        dto.getNextPaymentDate(),
        "soonest nextBillingAt wins (the MONTHLY member, +10 days)");
    // Amount is the real next charge: tariff share + EcoPay commission, not the bare per-member
    // price.
    assertEquals(0, new BigDecimal("3500.00").compareTo(dto.getNextPaymentAmountKzt()));
  }

  @Test
  void otherPeriodMembershipsAreSkippedForNextPayment() {
    User user = user(3L);
    Room otherRoom =
        roomKzt(
            200L,
            RoomStatus.ACTIVE,
            PeriodType.OTHER,
            new BigDecimal("5000"),
            new BigDecimal("3000"));
    RoomMember m = member(user, otherRoom, MemberStatus.ACTIVE);
    m.setNextBillingAt(LocalDateTime.now().plusDays(3));
    when(roomMemberRepository.findByUserAndDeletedAtIsNullOrderByCreatedAtDesc(user))
        .thenReturn(List.of(m));
    when(reviewRepository.countByRecipientAndHiddenByAdminFalse(user)).thenReturn(0L);
    when(disputeRepository.countByOpenedByUser(user)).thenReturn(0L);

    MemberDashboardDto dto = service.getMyDashboard(user);
    assertEquals(null, dto.getNextPaymentDate(), "OTHER-period plans have no renewal projection");
  }

  @Test
  void emptyMembershipReturnsZeroes() {
    User user = user(2L);
    when(roomMemberRepository.findByUserAndDeletedAtIsNullOrderByCreatedAtDesc(user))
        .thenReturn(Collections.emptyList());
    when(reviewRepository.countByRecipientAndHiddenByAdminFalse(user)).thenReturn(0L);
    when(disputeRepository.countByOpenedByUser(user)).thenReturn(0L);
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
