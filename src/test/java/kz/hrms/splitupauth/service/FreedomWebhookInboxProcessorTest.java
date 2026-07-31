package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import kz.hrms.splitupauth.entity.FreedomWebhookInbox;
import kz.hrms.splitupauth.payment.gateway.GatewayWebhookEvent;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway;
import kz.hrms.splitupauth.repository.FreedomWebhookInboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FreedomWebhookInboxProcessorTest {

  @Mock private FreedomWebhookInboxRepository repository;
  @Mock private FreedomPayGateway gateway;
  @Mock private PaymentService paymentService;
  @Mock private PayoutCardBindingService cardBindingService;

  private FreedomWebhookInboxProcessor processor;

  @BeforeEach
  void setUp() {
    processor =
        new FreedomWebhookInboxProcessor(
            repository, gateway, paymentService, cardBindingService);
  }

  @Test
  void validCharge_isAppliedAndMarkedProcessed() {
    Map<String, String> params =
        Map.of("pg_order_id", "42", "pg_result", "1", "pg_sig", "valid");
    FreedomWebhookInbox inbox = processingInbox(params);
    GatewayWebhookEvent event =
        GatewayWebhookEvent.builder()
            .kind("CHARGE")
            .intentId(42L)
            .resultStatus("SUCCESS")
            .build();
    when(repository.findClaimedWithLockById(1L, "worker-1"))
        .thenReturn(Optional.of(inbox));
    when(gateway.verifyWebhookSignature("result", params)).thenReturn(true);
    when(gateway.verifyAndParseWebhook("result", params)).thenReturn(event);
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    processor.processClaimed(1L, "worker-1");

    verify(paymentService).applyWebhookEvent(event);
    assertEquals("PROCESSED", inbox.getProcessingStatus());
    assertEquals(true, inbox.getSignatureValid());
    assertEquals(null, inbox.getLeaseOwner());
  }

  @Test
  void invalidSignature_isNonRetryableAndDoesNotTouchMoney() {
    Map<String, String> params = Map.of("pg_order_id", "42", "pg_sig", "invalid");
    FreedomWebhookInbox inbox = processingInbox(params);
    when(repository.findClaimedWithLockById(1L, "worker-1"))
        .thenReturn(Optional.of(inbox));
    when(gateway.verifyWebhookSignature("result", params)).thenReturn(false);

    FreedomWebhookProcessingException error =
        assertThrows(
            FreedomWebhookProcessingException.class,
            () -> processor.processClaimed(1L, "worker-1"));

    assertEquals("INVALID_SIGNATURE", error.getErrorCode());
    assertFalse(error.isRetryable());
    verify(paymentService, never()).applyWebhookEvent(any());
    verify(repository, never()).save(any());
  }

  private static FreedomWebhookInbox processingInbox(Map<String, String> params) {
    return FreedomWebhookInbox.builder()
        .id(1L)
        .providerRequestId("request-1")
        .callbackScript("result")
        .rawBody(new ObjectMapper().valueToTree(params))
        .processingStatus("PROCESSING")
        .attemptCount(1)
        .leaseOwner("worker-1")
        .build();
  }
}
