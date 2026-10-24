package kz.hrms.splitupauth.service;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import kz.hrms.splitupauth.dto.RevealIdentifierRequest;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.repository.PaymentTransactionRepository;
import kz.hrms.splitupauth.repository.RefundTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class IdentifierRevealPolicy {

  private static final Set<MemberStatus> OWNER_REVEAL_STATES =
      EnumSet.of(MemberStatus.PENDING, MemberStatus.ACTIVE);
  private static final Set<IdentifierRevealReasonCode> OWNER_REASONS =
      EnumSet.of(
          IdentifierRevealReasonCode.PROVIDE_SERVICE_ACCESS,
          IdentifierRevealReasonCode.RETRY_SERVICE_INVITE,
          IdentifierRevealReasonCode.RESOLVE_ACCESS_CONFIGURATION);

  private final PaymentTransactionRepository paymentTransactionRepository;
  private final RefundTransactionRepository refundTransactionRepository;

  public void canOwnerReveal(
      Room room, RoomMember roomMember, User currentUser, RevealIdentifierRequest request) {
    validateReasonDetails(request);
    if (!OWNER_REASONS.contains(request.getReasonCode())) {
      throw new InvalidRequestException("Unsupported owner reveal reason");
    }
    if (!room.getOwner().getId().equals(currentUser.getId())) {
      throw new ForbiddenOperationException("Only room owner can reveal member identifier");
    }
    ensureRevealableMembership(roomMember);
  }

  public void canStaffReveal(
      RoomMember roomMember,
      RevealIdentifierRequest request,
      IdentifierRevealContextType contextType) {
    validateReasonDetails(request);
    ensureRevealableMembership(roomMember);

    boolean allowed =
        switch (contextType) {
          case MODERATION ->
              request.getReasonCode() == IdentifierRevealReasonCode.MODERATION_REVIEW;
          case SUPPORT ->
              Set.of(
                      IdentifierRevealReasonCode.SUPPORT_TICKET,
                      IdentifierRevealReasonCode.ACCESS_ISSUE)
                  .contains(request.getReasonCode());
          case DISPUTE ->
              Set.of(
                      IdentifierRevealReasonCode.DISPUTE_INVESTIGATION,
                      IdentifierRevealReasonCode.FRAUD_REVIEW,
                      IdentifierRevealReasonCode.ACCESS_ISSUE)
                  .contains(request.getReasonCode());
        };
    if (!allowed) {
      throw new InvalidRequestException("Reveal reason is not allowed for this context");
    }
  }

  public void ensureValidSuccessfulPayment(RoomMember roomMember) {
    List<PaymentTransaction> successfulCharges =
        paymentTransactionRepository.findByRoomMember_IdAndStatusAndTypeOrderByCreatedAtDesc(
            roomMember.getId(), PaymentTransactionStatus.SUCCESS, PaymentTransactionType.CHARGE);

    boolean hasUsableCharge =
        successfulCharges.stream()
            .anyMatch(
                tx ->
                    tx.getAmount() != null
                        && tx.getAmount()
                                .subtract(refundTransactionRepository.sumActiveRefundAmounts(tx))
                                .compareTo(BigDecimal.ZERO)
                            > 0);

    if (!hasUsableCharge) {
      throw new ForbiddenOperationException(
          "Identifier can only be revealed after successful non-refunded payment");
    }
  }

  private void ensureRevealableMembership(RoomMember roomMember) {
    if (roomMember.getDeletedAt() != null) {
      throw new ForbiddenOperationException("Membership is not available for identifier reveal");
    }
    if (roomMember.getUser() == null || roomMember.getUser().getStatus() == UserStatus.BANNED) {
      throw new ForbiddenOperationException(
          "Membership user is not available for identifier reveal");
    }
    if (!OWNER_REVEAL_STATES.contains(roomMember.getStatus())) {
      throw new ForbiddenOperationException("Membership state does not allow identifier reveal");
    }
  }

  private void validateReasonDetails(RevealIdentifierRequest request) {
    if (request.getReasonCode() == null) {
      throw new InvalidRequestException("Reason code is required");
    }
    String details = request.getReasonDetails();
    if (details != null && details.isBlank()) {
      throw new InvalidRequestException("Reason details must not be blank");
    }
  }
}
