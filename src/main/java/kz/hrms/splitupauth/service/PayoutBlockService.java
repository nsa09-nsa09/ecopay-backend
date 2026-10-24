package kz.hrms.splitupauth.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.repository.PayoutBlockRepository;
import kz.hrms.splitupauth.repository.PayoutRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PayoutBlockService {

  private static final List<PayoutStatus> BLOCKABLE_STATUSES =
      List.of(PayoutStatus.PENDING, PayoutStatus.PENDING_METHOD, PayoutStatus.FROZEN);

  private final PayoutRepository payoutRepository;
  private final PayoutBlockRepository payoutBlockRepository;

  @Transactional
  public void blockForPaymentIntent(
      PaymentIntent intent, PayoutBlockSourceType sourceType, Long sourceId, String reasonCode) {
    if (intent == null || sourceId == null) return;
    Payout payout = payoutRepository.findByTriggeringPaymentIntent(intent).orElse(null);
    if (payout == null) return;
    Payout locked = payoutRepository.findWithLockById(payout.getId()).orElse(null);
    if (locked == null || !BLOCKABLE_STATUSES.contains(locked.getStatus())) return;
    createBlockIfMissing(locked, sourceType, sourceId, reasonCode);
  }

  @Transactional
  public void blockForRoomMember(
      RoomMember roomMember, PayoutBlockSourceType sourceType, Long sourceId, String reasonCode) {
    if (roomMember == null || sourceId == null) return;
    for (Payout payout :
        payoutRepository.findWithLockByRoomMemberAndStatusIn(roomMember, BLOCKABLE_STATUSES)) {
      createBlockIfMissing(payout, sourceType, sourceId, reasonCode);
    }
  }

  /**
   * Release any blocks created for this room member under a given (sourceType, sourceId). Mirrors
   * {@link #releaseForPaymentIntent} but keyed on the member — the dispute case does not know which
   * payment intents are involved, so looking them up here keeps the caller simple. If a payout is
   * only held by this one block, it transitions back to PENDING / PENDING_METHOD and can resume
   * normal dispatch.
   */
  @Transactional
  public void releaseForRoomMember(
      RoomMember roomMember, PayoutBlockSourceType sourceType, Long sourceId) {
    if (roomMember == null || sourceId == null) return;
    // Include every non-terminal status so a payout already PROCESSING/COMPLETED is not touched.
    List<PayoutStatus> allHoldable =
        List.of(
            PayoutStatus.PENDING,
            PayoutStatus.PENDING_METHOD,
            PayoutStatus.FROZEN,
            PayoutStatus.PROCESSING);
    for (Payout payout :
        payoutRepository.findWithLockByRoomMemberAndStatusIn(roomMember, allHoldable)) {
      PayoutBlock block =
          payoutBlockRepository
              .findByPayoutAndSourceTypeAndSourceId(payout, sourceType, sourceId)
              .orElse(null);
      if (block == null || block.getStatus() == PayoutBlockStatus.RELEASED) continue;

      block.setStatus(PayoutBlockStatus.RELEASED);
      block.setReleasedAt(LocalDateTime.now());
      payoutBlockRepository.saveAndFlush(block);

      if (!payoutBlockRepository.existsByPayoutAndStatus(payout, PayoutBlockStatus.ACTIVE)
          && payout.getStatus() == PayoutStatus.FROZEN) {
        if (payout.getPayableAmount() != null
            && payout.getPayableAmount().compareTo(BigDecimal.ZERO) <= 0) {
          payout.setStatus(PayoutStatus.REVERSED);
        } else {
          payout.setStatus(
              payout.getPayoutMethod() == null
                  ? PayoutStatus.PENDING_METHOD
                  : PayoutStatus.PENDING);
        }
        payout.setFailureReason(null);
        payoutRepository.save(payout);
      }
    }
  }

  @Transactional
  public void releaseForPaymentIntent(
      PaymentIntent intent, PayoutBlockSourceType sourceType, Long sourceId) {
    if (intent == null || sourceId == null) return;
    Payout payout = payoutRepository.findByTriggeringPaymentIntent(intent).orElse(null);
    if (payout == null) return;
    Payout locked = payoutRepository.findWithLockById(payout.getId()).orElse(null);
    if (locked == null) return;

    PayoutBlock block =
        payoutBlockRepository
            .findByPayoutAndSourceTypeAndSourceId(locked, sourceType, sourceId)
            .orElse(null);
    if (block == null || block.getStatus() == PayoutBlockStatus.RELEASED) return;

    block.setStatus(PayoutBlockStatus.RELEASED);
    block.setReleasedAt(LocalDateTime.now());
    payoutBlockRepository.saveAndFlush(block);

    if (!payoutBlockRepository.existsByPayoutAndStatus(locked, PayoutBlockStatus.ACTIVE)
        && locked.getStatus() == PayoutStatus.FROZEN) {
      if (locked.getPayableAmount() != null
          && locked.getPayableAmount().compareTo(BigDecimal.ZERO) <= 0) {
        locked.setStatus(PayoutStatus.REVERSED);
      } else {
        locked.setStatus(
            locked.getPayoutMethod() == null ? PayoutStatus.PENDING_METHOD : PayoutStatus.PENDING);
      }
      locked.setFailureReason(null);
      payoutRepository.save(locked);
    }
  }

  private void createBlockIfMissing(
      Payout payout, PayoutBlockSourceType sourceType, Long sourceId, String reasonCode) {
    if (payoutBlockRepository
        .findByPayoutAndSourceTypeAndSourceId(payout, sourceType, sourceId)
        .isPresent()) {
      return;
    }
    payoutBlockRepository.save(
        PayoutBlock.builder()
            .payout(payout)
            .sourceType(sourceType)
            .sourceId(sourceId)
            .reasonCode(reasonCode)
            .status(PayoutBlockStatus.ACTIVE)
            .build());
    if (payout.getStatus() == PayoutStatus.PENDING
        || payout.getStatus() == PayoutStatus.PENDING_METHOD) {
      payout.setStatus(PayoutStatus.FROZEN);
      payout.setFailureReason("Blocked: " + reasonCode);
      payoutRepository.save(payout);
    }
  }
}
