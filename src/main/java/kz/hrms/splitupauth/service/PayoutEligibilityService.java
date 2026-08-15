package kz.hrms.splitupauth.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import kz.hrms.splitupauth.entity.DisputeStatus;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import kz.hrms.splitupauth.entity.PaymentTransactionStatus;
import kz.hrms.splitupauth.entity.PaymentTransactionType;
import kz.hrms.splitupauth.entity.Payout;
import kz.hrms.splitupauth.entity.RefundStatus;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.repository.DisputeRepository;
import kz.hrms.splitupauth.repository.PaymentTransactionRepository;
import kz.hrms.splitupauth.repository.RefundTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PayoutEligibilityService {

  private final PaymentTransactionRepository paymentTransactionRepository;
  private final RefundTransactionRepository refundTransactionRepository;
  private final DisputeRepository disputeRepository;
  private final Clock clock;

  public Decision evaluate(Payout payout) {
    LocalDateTime now = LocalDateTime.now(clock);
    if (payout == null) {
      return Decision.blocked("PAYOUT_MISSING");
    }
    if (payout.getReleaseAt() != null && payout.getReleaseAt().isAfter(now)) {
      return Decision.waiting("RELEASE_AT_NOT_REACHED");
    }

    PaymentIntent intent = payout.getTriggeringPaymentIntent();
    if (intent == null || intent.getStatus() != PaymentIntentStatus.SUCCESS) {
      return Decision.blocked("CHARGE_NOT_SUCCESSFUL");
    }
    if (Boolean.TRUE.equals(intent.getReviewRequired())
        || Boolean.TRUE.equals(intent.getCompensationRequired())) {
      return Decision.blocked("PAYMENT_REQUIRES_REVIEW");
    }
    if (paymentTransactionRepository
        .findFirstByPaymentIntentAndTypeAndStatus(
            intent, PaymentTransactionType.CHARGE, PaymentTransactionStatus.SUCCESS)
        .isEmpty()) {
      return Decision.blocked("CAPTURE_TRANSACTION_MISSING");
    }
    if (refundTransactionRepository.existsByPaymentIntentAndStatusIn(
        intent, List.of(RefundStatus.PENDING, RefundStatus.FAILED, RefundStatus.REQUIRES_REVIEW))) {
      return Decision.blocked("REFUND_ACTIVE");
    }

    RoomMember member = intent.getRoomMember();
    if (member == null
        || member.getStatus() != MemberStatus.ACTIVE
        || member.getActivatedAt() == null
        || member.getOwnerAccessConfirmedAt() == null
        || member.getMemberConfirmedAt() == null
        || Boolean.TRUE.equals(member.getRequiresAdminReview())) {
      return Decision.waiting("ACCESS_NOT_CONFIRMED");
    }

    Room room = member.getRoom();
    if (room == null
        || room.getStatus() == RoomStatus.CANCELLED
        || room.getStatus() == RoomStatus.BLOCKED
        || room.getStatus() == RoomStatus.OPEN
        || room.getStatus() == RoomStatus.IN_VERIFICATION) {
      return Decision.blocked("ROOM_NOT_ELIGIBLE");
    }
    if (disputeRepository.existsByRoomAndStatusIn(
        room, List.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW))) {
      return Decision.blocked("DISPUTE_OPEN");
    }

    User owner = payout.getUser();
    if (owner == null
        || owner.getDeletedAt() != null
        || owner.getStatus() == UserStatus.DELETED
        || owner.getStatus() == UserStatus.BANNED) {
      return Decision.blocked("OWNER_NOT_ELIGIBLE");
    }
    return Decision.allowed();
  }

  public record Decision(boolean eligible, boolean temporary, String reason) {
    public static Decision allowed() {
      return new Decision(true, false, null);
    }

    public static Decision waiting(String reason) {
      return new Decision(false, true, reason);
    }

    public static Decision blocked(String reason) {
      return new Decision(false, false, reason);
    }
  }
}
