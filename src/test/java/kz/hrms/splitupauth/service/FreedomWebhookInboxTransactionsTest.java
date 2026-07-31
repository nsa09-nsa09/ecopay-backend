package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import kz.hrms.splitupauth.entity.FreedomWebhookInbox;
import kz.hrms.splitupauth.repository.FreedomWebhookInboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FreedomWebhookInboxTransactionsTest {

  @Mock private FreedomWebhookInboxRepository repository;

  private FreedomWebhookInboxTransactions transactions;

  @BeforeEach
  void setUp() {
    transactions = new FreedomWebhookInboxTransactions(repository);
  }

  @Test
  void retryableFailureBeforeLimit_isScheduledAgain() {
    FreedomWebhookInbox inbox = processingInbox(2);
    when(repository.findWithLockById(1L)).thenReturn(Optional.of(inbox));
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    LocalDateTime retryAt = LocalDateTime.now().plusMinutes(1);

    transactions.recordFailure(
        1L, "worker-1", 2, 3, true, "TEMPORARY", "try again", retryAt);

    assertEquals("FAILED", inbox.getProcessingStatus());
    assertEquals(retryAt, inbox.getNextRetryAt());
    assertNull(inbox.getLeaseOwner());
    assertNull(inbox.getLeaseUntil());
    assertNull(inbox.getDeadLetteredAt());
  }

  @Test
  void retryableFailureAtLimit_isMovedToDeadLetter() {
    FreedomWebhookInbox inbox = processingInbox(3);
    when(repository.findWithLockById(1L)).thenReturn(Optional.of(inbox));
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    transactions.recordFailure(
        1L,
        "worker-1",
        3,
        3,
        true,
        "DATABASE_ERROR",
        "still unavailable",
        LocalDateTime.now().plusMinutes(2));

    assertEquals("DEAD_LETTER", inbox.getProcessingStatus());
    assertEquals("DATABASE_ERROR", inbox.getLastErrorCode());
    assertNull(inbox.getNextRetryAt());
    assertNotNull(inbox.getDeadLetteredAt());
    assertNotNull(inbox.getProcessedAt());
  }

  @Test
  void staleWorkerCannotOverwriteANewerLease() {
    FreedomWebhookInbox inbox = processingInbox(3);
    inbox.setLeaseOwner("new-worker");
    when(repository.findWithLockById(1L)).thenReturn(Optional.of(inbox));

    transactions.recordFailure(
        1L,
        "old-worker",
        2,
        3,
        true,
        "OLD_FAILURE",
        "late result",
        LocalDateTime.now());

    assertEquals("PROCESSING", inbox.getProcessingStatus());
    assertEquals("new-worker", inbox.getLeaseOwner());
    assertNull(inbox.getLastErrorCode());
  }

  private static FreedomWebhookInbox processingInbox(int attempt) {
    return FreedomWebhookInbox.builder()
        .id(1L)
        .providerRequestId("request-1")
        .rawBody(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode())
        .processingStatus("PROCESSING")
        .attemptCount(attempt)
        .leaseOwner("worker-1")
        .leaseUntil(LocalDateTime.now().plusMinutes(5))
        .build();
  }
}
