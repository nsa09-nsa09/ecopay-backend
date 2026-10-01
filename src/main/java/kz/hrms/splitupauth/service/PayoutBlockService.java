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

  private static final List<String> BLOCKABLE_STATUSES =
      List.of("PENDING", "PENDING_METHOD", "FROZEN");

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
        && "FROZEN".equals(locked.getStatus())) {
      if (locked.getPayableAmount() != null
          && locked.getPayableAmount().compareTo(BigDecimal.ZERO) <= 0) {
        locked.setStatus("REVERSED");
      } else {
        locked.setStatus(locked.getPayoutMethod() == null ? "PENDING_METHOD" : "PENDING");
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
    if ("PENDING".equals(payout.getStatus()) || "PENDING_METHOD".equals(payout.getStatus())) {
      payout.setStatus("FROZEN");
      payout.setFailureReason("Blocked: " + reasonCode);
      payoutRepository.save(payout);
    }
  }
}
