package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.dto.MemberHoldDto;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.Payout;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.PayoutRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberHoldServiceTest {

  @Mock private RoomRepository roomRepository;
  @Mock private RoomMemberRepository roomMemberRepository;
  @Mock private PayoutRepository payoutRepository;

  private final Clock clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneId.of("UTC"));
  private MemberHoldService memberHoldService;

  @BeforeEach
  void setUp() {
    memberHoldService =
        new MemberHoldService(roomRepository, roomMemberRepository, payoutRepository, clock);
  }

  @Test
  void getMyHoldUsesOnlyOwnMemberHeldPayoutsAndPayableAmounts() {
    User owner =
        User.builder().id(1L).displayName("Ivan").publicId("owner-public").slug("ivan").build();
    User member = User.builder().id(2L).build();
    Room room = Room.builder().id(10L).owner(owner).build();
    RoomMember membership = RoomMember.builder().id(20L).room(room).user(member).build();
    LocalDateTime firstRelease = LocalDateTime.of(2026, 10, 10, 0, 0);
    LocalDateTime secondRelease = LocalDateTime.of(2026, 10, 22, 0, 0);
    Payout pending = payout(membership, "PENDING", "9000.00", "5000.25", secondRelease);
    Payout frozen = payout(membership, "FROZEN", "4000.00", "831.42", firstRelease);
    stubMembership(room, member, membership);
    when(payoutRepository.findHeldByRoomMember(
            eq(membership),
            eq("KZT"),
            eq(List.of("PENDING", "PENDING_METHOD", "FROZEN")),
            eq(LocalDateTime.of(2026, 10, 1, 0, 0))))
        .thenReturn(List.of(pending, frozen));

    MemberHoldDto hold = memberHoldService.getMyHold(10L, member);

    assertEquals(0, new BigDecimal("5831.67").compareTo(hold.getHeldAmount()));
    assertEquals(2L, hold.getHeldPayoutCount());
    assertEquals(firstRelease, hold.getNextReleaseAt());
    assertEquals(1L, hold.getBeneficiaryUserId());
    assertEquals("Ivan", hold.getBeneficiaryDisplayName());
    assertEquals("owner-public", hold.getBeneficiaryPublicId());
    assertEquals("ivan", hold.getBeneficiarySlug());
    verify(payoutRepository)
        .findHeldByRoomMember(
            membership,
            "KZT",
            List.of("PENDING", "PENDING_METHOD", "FROZEN"),
            LocalDateTime.of(2026, 10, 1, 0, 0));
  }

  @Test
  void getMyHoldReturnsZeroWhenNoActiveHoldExists() {
    User owner = User.builder().id(1L).displayName("Owner").build();
    User member = User.builder().id(2L).build();
    Room room = Room.builder().id(10L).owner(owner).build();
    RoomMember membership = RoomMember.builder().id(20L).room(room).user(member).build();
    stubMembership(room, member, membership);
    when(payoutRepository.findHeldByRoomMember(
            eq(membership),
            eq("KZT"),
            eq(List.of("PENDING", "PENDING_METHOD", "FROZEN")),
            eq(LocalDateTime.of(2026, 10, 1, 0, 0))))
        .thenReturn(List.of());

    MemberHoldDto hold = memberHoldService.getMyHold(10L, member);

    assertEquals(0, new BigDecimal("0.00").compareTo(hold.getHeldAmount()));
    assertEquals(0L, hold.getHeldPayoutCount());
    assertNull(hold.getNextReleaseAt());
  }

  @Test
  void userCannotReadAnotherMembersHold() {
    User owner = User.builder().id(1L).build();
    User requester = User.builder().id(3L).build();
    Room room = Room.builder().id(10L).owner(owner).build();
    when(roomRepository.findById(10L)).thenReturn(Optional.of(room));
    when(roomMemberRepository.findByRoomAndUserAndDeletedAtIsNull(room, requester))
        .thenReturn(Optional.empty());

    assertThrows(
        ResourceNotFoundException.class, () -> memberHoldService.getMyHold(10L, requester));
    verifyNoInteractions(payoutRepository);
  }

  private void stubMembership(Room room, User member, RoomMember membership) {
    when(roomRepository.findById(room.getId())).thenReturn(Optional.of(room));
    when(roomMemberRepository.findByRoomAndUserAndDeletedAtIsNull(room, member))
        .thenReturn(Optional.of(membership));
  }

  private Payout payout(
      RoomMember membership,
      String status,
      String originalAmount,
      String payableAmount,
      LocalDateTime releaseAt) {
    return Payout.builder()
        .triggeringPaymentIntent(PaymentIntent.builder().roomMember(membership).build())
        .status(status)
        .amount(new BigDecimal(originalAmount))
        .payableAmount(new BigDecimal(payableAmount))
        .releaseAt(releaseAt)
        .build();
  }
}
