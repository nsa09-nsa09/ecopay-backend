package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import kz.hrms.splitupauth.dto.RevealIdentifierRequest;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.repository.PaymentTransactionRepository;
import kz.hrms.splitupauth.repository.RefundTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdentifierRevealPolicyTest {

  @Mock private PaymentTransactionRepository paymentTransactionRepository;
  @Mock private RefundTransactionRepository refundTransactionRepository;

  private IdentifierRevealPolicy policy;

  @BeforeEach
  void setUp() {
    policy = new IdentifierRevealPolicy(paymentTransactionRepository, refundTransactionRepository);
  }

  @Test
  void canOwnerReveal_rejectsStaffOnlyReason() {
    User owner = user(1L, Role.USER);
    Room room = room(owner);
    RoomMember member = membership(room, user(2L, Role.USER));
    RevealIdentifierRequest request = request(IdentifierRevealReasonCode.MODERATION_REVIEW);

    assertThrows(
        InvalidRequestException.class, () -> policy.canOwnerReveal(room, member, owner, request));
  }

  @Test
  void ensureValidSuccessfulPayment_rejectsFullyRefundedCharge() {
    RoomMember member = membership(room(user(1L, Role.USER)), user(2L, Role.USER));
    PaymentTransaction tx = charge(member, "1000.00");

    when(paymentTransactionRepository.findByRoomMember_IdAndStatusAndTypeOrderByCreatedAtDesc(
            member.getId(), PaymentTransactionStatus.SUCCESS, PaymentTransactionType.CHARGE))
        .thenReturn(List.of(tx));
    when(refundTransactionRepository.sumActiveRefundAmounts(tx))
        .thenReturn(new BigDecimal("1000.00"));

    assertThrows(
        ForbiddenOperationException.class, () -> policy.ensureValidSuccessfulPayment(member));
  }

  @Test
  void ensureValidSuccessfulPayment_allowsPartiallyRefundedCharge() {
    RoomMember member = membership(room(user(1L, Role.USER)), user(2L, Role.USER));
    PaymentTransaction tx = charge(member, "1000.00");

    when(paymentTransactionRepository.findByRoomMember_IdAndStatusAndTypeOrderByCreatedAtDesc(
            member.getId(), PaymentTransactionStatus.SUCCESS, PaymentTransactionType.CHARGE))
        .thenReturn(List.of(tx));
    when(refundTransactionRepository.sumActiveRefundAmounts(tx))
        .thenReturn(new BigDecimal("250.00"));

    assertDoesNotThrow(() -> policy.ensureValidSuccessfulPayment(member));
  }

  private RevealIdentifierRequest request(IdentifierRevealReasonCode reasonCode) {
    RevealIdentifierRequest request = new RevealIdentifierRequest();
    request.setReasonCode(reasonCode);
    return request;
  }

  private PaymentTransaction charge(RoomMember member, String amount) {
    return PaymentTransaction.builder()
        .id(900L)
        .roomMember(member)
        .type(PaymentTransactionType.CHARGE)
        .status(PaymentTransactionStatus.SUCCESS)
        .amount(new BigDecimal(amount))
        .createdAt(LocalDateTime.now())
        .build();
  }

  private Room room(User owner) {
    return Room.builder()
        .id(100L)
        .owner(owner)
        .roomType(RoomType.TELECOM)
        .status(RoomStatus.OPEN)
        .build();
  }

  private RoomMember membership(Room room, User member) {
    return RoomMember.builder()
        .id(200L)
        .room(room)
        .user(member)
        .status(MemberStatus.PENDING)
        .build();
  }

  private User user(Long id, Role role) {
    return User.builder()
        .id(id)
        .role(role)
        .displayName("User " + id)
        .status(UserStatus.ACTIVE)
        .build();
  }
}
