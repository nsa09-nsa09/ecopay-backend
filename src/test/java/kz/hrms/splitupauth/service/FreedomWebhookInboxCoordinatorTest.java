package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.OptionalInt;
import kz.hrms.splitupauth.entity.FreedomWebhookInbox;
import kz.hrms.splitupauth.payment.gateway.GatewayWebhookEvent;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class FreedomWebhookInboxCoordinatorTest {

  @Mock private FreedomPayGateway gateway;
  @Mock private FreedomWebhookInboxTransactions transactions;
  @Mock private FreedomWebhookInboxProcessor processor;

  private FreedomWebhookInboxCoordinator coordinator;

  @BeforeEach
  void setUp() {
    coordinator =
        new FreedomWebhookInboxCoordinator(
            gateway, new ObjectMapper(), transactions, processor);
    ReflectionTestUtils.setField(coordinator, "maxAttempts", 3);
    ReflectionTestUtils.setField(coordinator, "retryBaseSeconds", 30L);
    ReflectionTestUtils.setField(coordinator, "retryMaxSeconds", 3600L);
    ReflectionTestUtils.setField(coordinator, "leaseSeconds", 300L);
    ReflectionTestUtils.setField(coordinator, "batchSize", 100);
  }

  @Test
  void validCallback_isStoredBeforeItIsClaimedAndProcessed() {
    Map<String, String> params = Map.of("pg_order_id", "42", "pg_sig", "valid");
    when(gateway.verifyWebhookSignature("result", params)).thenReturn(true);
    when(gateway.verifyAndParseWebhook("result", params))
        .thenReturn(GatewayWebhookEvent.builder().providerRequestId("request-42").build());
    when(transactions.insert(any()))
        .thenAnswer(
            invocation -> {
              FreedomWebhookInbox inbox = invocation.getArgument(0);
              inbox.setId(7L);
              return inbox;
            });
    when(transactions.claim(eq(7L), anyString(), any(), any()))
        .thenReturn(OptionalInt.of(1));
    when(processor.processClaimed(eq(7L), anyString())).thenReturn(true);

    coordinator.acceptAndProcess("result", params);

    var order = org.mockito.Mockito.inOrder(transactions, processor);
    order.verify(transactions).insert(any());
    order.verify(transactions).claim(eq(7L), anyString(), any(), any());
    order.verify(processor).processClaimed(eq(7L), anyString());
  }

  @Test
  void invalidSignature_isDurablyDeadLetteredWithoutProcessing() {
    Map<String, String> params = Map.of("pg_order_id", "42", "pg_sig", "bad");
    when(gateway.verifyWebhookSignature("result", params)).thenReturn(false);
    when(transactions.insert(any()))
        .thenAnswer(
            invocation -> {
              FreedomWebhookInbox inbox = invocation.getArgument(0);
              inbox.setId(9L);
              return inbox;
            });

    FreedomWebhookInboxCoordinator.Acceptance result =
        coordinator.acceptAndProcess("result", params);

    assertTrue(result.invalidSignature());
    verify(transactions, never()).claim(any(), anyString(), any(), any());
    verify(processor, never()).processClaimed(any(), anyString());
  }

  @Test
  void retryableFailure_isRecordedWithExponentialBackoff() {
    when(transactions.claim(eq(11L), anyString(), any(), any()))
        .thenReturn(OptionalInt.of(2));
    when(processor.processClaimed(eq(11L), anyString()))
        .thenThrow(new IllegalStateException("database unavailable"));

    coordinator.processInbox(11L);

    verify(transactions)
        .recordFailure(
            eq(11L),
            anyString(),
            eq(2),
            eq(3),
            eq(true),
            eq("IllegalStateException"),
            eq("database unavailable"),
            any(LocalDateTime.class));
  }

  @Test
  void nonRetryableFailure_isSentStraightToDeadLetter() {
    when(transactions.claim(eq(12L), anyString(), any(), any()))
        .thenReturn(OptionalInt.of(1));
    when(processor.processClaimed(eq(12L), anyString()))
        .thenThrow(
            new FreedomWebhookProcessingException(
                "INVALID_SIGNATURE", "signature failed", false));

    coordinator.processInbox(12L);

    verify(transactions)
        .recordFailure(
            eq(12L),
            anyString(),
            eq(1),
            eq(3),
            eq(false),
            eq("INVALID_SIGNATURE"),
            eq("signature failed"),
            any(LocalDateTime.class));
  }
}
