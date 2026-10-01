package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.repository.PayoutBlockRepository;
import kz.hrms.splitupauth.repository.PayoutRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PayoutBlockServiceTest {

  @Mock private PayoutRepository payoutRepository;
  @Mock private PayoutBlockRepository payoutBlockRepository;
  private PayoutBlockService service;

  @BeforeEach
  void setUp() {
    service = new PayoutBlockService(payoutRepository, payoutBlockRepository);
  }

  @Test
  void blockLocksPendingPayoutAndMakesItFrozen() {
    PaymentIntent intent = PaymentIntent.builder().id(1L).build();
    Payout payout = Payout.builder().id(2L).status("PENDING").build();
    when(payoutRepository.findByTriggeringPaymentIntent(intent)).thenReturn(Optional.of(payout));
    when(payoutRepository.findWithLockById(2L)).thenReturn(Optional.of(payout));

    service.blockForPaymentIntent(
        intent, PayoutBlockSourceType.REFUND_REQUEST, 3L, "ACCESS_NOT_WORKING");

    assertEquals("FROZEN", payout.getStatus());
    verify(payoutBlockRepository).save(any(PayoutBlock.class));
    verify(payoutRepository).save(payout);
  }

  @Test
  void processingPayoutCannotBeSilentlyReclassifiedAsFrozen() {
    PaymentIntent intent = PaymentIntent.builder().id(1L).build();
    Payout payout = Payout.builder().id(2L).status("PROCESSING").build();
    when(payoutRepository.findByTriggeringPaymentIntent(intent)).thenReturn(Optional.of(payout));
    when(payoutRepository.findWithLockById(2L)).thenReturn(Optional.of(payout));

    service.blockForPaymentIntent(
        intent, PayoutBlockSourceType.REFUND_REQUEST, 3L, "ACCESS_NOT_WORKING");

    assertEquals("PROCESSING", payout.getStatus());
    verify(payoutBlockRepository, never()).save(any());
  }

  @Test
  void releasingLastBlockMakesRemainingPayoutDispatchableAgain() {
    PaymentIntent intent = PaymentIntent.builder().id(1L).build();
    Payout payout =
        Payout.builder()
            .id(2L)
            .status("FROZEN")
            .payoutMethod(PayoutMethod.builder().id(9L).build())
            .payableAmount(new BigDecimal("2500.00"))
            .failureReason("Blocked")
            .build();
    PayoutBlock block =
        PayoutBlock.builder()
            .id(3L)
            .payout(payout)
            .sourceType(PayoutBlockSourceType.REFUND_REQUEST)
            .sourceId(4L)
            .status(PayoutBlockStatus.ACTIVE)
            .build();
    when(payoutRepository.findByTriggeringPaymentIntent(intent)).thenReturn(Optional.of(payout));
    when(payoutRepository.findWithLockById(2L)).thenReturn(Optional.of(payout));
    when(payoutBlockRepository.findByPayoutAndSourceTypeAndSourceId(
            payout, PayoutBlockSourceType.REFUND_REQUEST, 4L))
        .thenReturn(Optional.of(block));
    when(payoutBlockRepository.existsByPayoutAndStatus(payout, PayoutBlockStatus.ACTIVE))
        .thenReturn(false);

    service.releaseForPaymentIntent(intent, PayoutBlockSourceType.REFUND_REQUEST, 4L);

    assertEquals(PayoutBlockStatus.RELEASED, block.getStatus());
    assertEquals("PENDING", payout.getStatus());
    assertEquals(null, payout.getFailureReason());
    verify(payoutBlockRepository).saveAndFlush(block);
    verify(payoutRepository).save(payout);
  }
}
