package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import kz.hrms.splitupauth.dto.ApproveRefundClaimRequest;
import kz.hrms.splitupauth.dto.CreateRefundClaimRequest;
import kz.hrms.splitupauth.dto.RefundTransactionResponse;
import kz.hrms.splitupauth.dto.RejectRefundClaimRequest;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.PaymentTransactionRepository;
import kz.hrms.splitupauth.repository.RefundRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefundRequestServiceTest {

  @Mock private RefundRequestRepository refundRequestRepository;
  @Mock private PaymentTransactionRepository paymentTransactionRepository;
  @Mock private PayoutBlockService payoutBlockService;
  @Mock private RefundService refundService;
  @Mock private AdminActionLogRepository adminActionLogRepository;

  private RefundRequestService service;

  @BeforeEach
  void setUp() {
    service =
        new RefundRequestService(
            refundRequestRepository,
            paymentTransactionRepository,
            payoutBlockService,
            refundService,
            adminActionLogRepository,
            Clock.systemUTC());
  }

  @Test
  void payerCreatesClaimAndFreezesPayoutWithoutCreatingMoneyOperation() {
    User payer = User.builder().id(10L).build();
    PaymentIntent intent =
        PaymentIntent.builder().id(20L).user(payer).amount(new BigDecimal("5700.00")).build();
    PaymentTransaction tx =
        PaymentTransaction.builder()
            .id(30L)
            .paymentIntent(intent)
            .type(PaymentTransactionType.CHARGE)
            .status(PaymentTransactionStatus.SUCCESS)
            .amount(new BigDecimal("5700.00"))
            .build();
    CreateRefundClaimRequest request = request();
    when(paymentTransactionRepository.findWithLockById(30L)).thenReturn(Optional.of(tx));
    when(refundRequestRepository.save(any()))
        .thenAnswer(
            invocation -> {
              RefundRequest saved = invocation.getArgument(0);
              saved.setId(40L);
              return saved;
            });

    var response = service.create(payer, request);

    assertEquals(RefundRequestStatus.REQUESTED, response.getStatus());
    assertEquals(40L, response.getId());
    verify(payoutBlockService)
        .blockForPaymentIntent(
            intent,
            PayoutBlockSourceType.REFUND_REQUEST,
            40L,
            RefundReasonCode.ACCESS_NOT_WORKING.name());
  }

  @Test
  void cannotReuseAnotherUsersIdempotencyKey() {
    User payer = User.builder().id(10L).build();
    RefundRequest existing =
        RefundRequest.builder()
            .requester(User.builder().id(99L).build())
            .idempotencyKey("claim-1")
            .build();
    when(refundRequestRepository.findByIdempotencyKey("claim-1")).thenReturn(Optional.of(existing));

    assertThrows(ForbiddenOperationException.class, () -> service.create(payer, request()));
  }

  @Test
  void approvingClaimCreatesMoneyOperationButKeepsPayoutBlockedUntilProviderSuccess() {
    User admin = User.builder().id(1L).role(Role.ADMIN).build();
    PaymentIntent intent = PaymentIntent.builder().id(20L).build();
    PaymentTransaction tx = PaymentTransaction.builder().id(30L).paymentIntent(intent).build();
    RefundRequest claim =
        RefundRequest.builder()
            .id(40L)
            .paymentTransaction(tx)
            .requester(User.builder().id(10L).build())
            .status(RefundRequestStatus.REQUESTED)
            .build();
    ApproveRefundClaimRequest decision = new ApproveRefundClaimRequest();
    decision.setAmount(new BigDecimal("1200.00"));
    decision.setDecisionNote("Evidence reviewed");
    HttpServletRequest httpRequest = org.mockito.Mockito.mock(HttpServletRequest.class);
    when(refundRequestRepository.findWithLockById(40L)).thenReturn(Optional.of(claim));
    when(refundService.createApprovedRefund(
            admin, claim, decision.getAmount(), decision.getDecisionNote(), httpRequest))
        .thenReturn(RefundTransactionResponse.builder().amount(new BigDecimal("1200.00")).build());

    var response = service.approve(40L, admin, decision, httpRequest);

    assertEquals(RefundRequestStatus.APPROVED, response.getStatus());
    assertEquals(new BigDecimal("1200.00"), response.getApprovedAmount());
    verify(payoutBlockService, never()).releaseForPaymentIntent(any(), any(), any());
  }

  @Test
  void rejectingClaimReleasesItsPayoutBlock() {
    User admin = User.builder().id(1L).role(Role.ADMIN).build();
    PaymentIntent intent = PaymentIntent.builder().id(20L).build();
    RefundRequest claim =
        RefundRequest.builder()
            .id(40L)
            .paymentTransaction(PaymentTransaction.builder().id(30L).paymentIntent(intent).build())
            .requester(User.builder().id(10L).build())
            .status(RefundRequestStatus.REQUESTED)
            .build();
    RejectRefundClaimRequest decision = new RejectRefundClaimRequest();
    decision.setDecisionNote("Evidence does not support the claim");
    HttpServletRequest httpRequest = org.mockito.Mockito.mock(HttpServletRequest.class);
    when(refundRequestRepository.findWithLockById(40L)).thenReturn(Optional.of(claim));

    var response = service.reject(40L, admin, decision, httpRequest);

    assertEquals(RefundRequestStatus.REJECTED, response.getStatus());
    verify(payoutBlockService)
        .releaseForPaymentIntent(intent, PayoutBlockSourceType.REFUND_REQUEST, 40L);
  }

  private static CreateRefundClaimRequest request() {
    CreateRefundClaimRequest request = new CreateRefundClaimRequest();
    request.setPaymentTransactionId(30L);
    request.setReasonCode(RefundReasonCode.ACCESS_NOT_WORKING);
    request.setDescription("Доступ не открывается");
    request.setIdempotencyKey("claim-1");
    return request;
  }
}
