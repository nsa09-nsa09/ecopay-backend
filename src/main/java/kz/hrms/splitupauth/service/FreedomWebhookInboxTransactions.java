package kz.hrms.splitupauth.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import kz.hrms.splitupauth.entity.FreedomWebhookInbox;
import kz.hrms.splitupauth.repository.FreedomWebhookInboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short, independent transactions for inbox persistence and lease state. */
@Service
@RequiredArgsConstructor
public class FreedomWebhookInboxTransactions {

  private final FreedomWebhookInboxRepository inboxRepository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public FreedomWebhookInbox insert(FreedomWebhookInbox inbox) {
    return inboxRepository.saveAndFlush(inbox);
  }

  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  public Optional<FreedomWebhookInbox> findByProviderRequestId(String providerRequestId) {
    return inboxRepository.findByProviderRequestId(providerRequestId);
  }

  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  public List<Long> findRetryableIds(LocalDateTime now, int batchSize) {
    return inboxRepository.findRetryableIds(now, PageRequest.of(0, Math.max(1, batchSize)));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public OptionalInt claim(
      Long inboxId, String leaseOwner, LocalDateTime now, LocalDateTime leaseUntil) {
    if (inboxRepository.claimForProcessing(inboxId, leaseOwner, now, leaseUntil) == 0) {
      return OptionalInt.empty();
    }
    int attempts =
        inboxRepository
            .findById(inboxId)
            .map(FreedomWebhookInbox::getAttemptCount)
            .orElse(1);
    return OptionalInt.of(attempts);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailure(
      Long inboxId,
      String leaseOwner,
      int claimedAttempt,
      int maxAttempts,
      boolean retryable,
      String errorCode,
      String errorMessage,
      LocalDateTime nextRetryAt) {
    FreedomWebhookInbox inbox = inboxRepository.findWithLockById(inboxId).orElse(null);
    if (inbox == null
        || !"PROCESSING".equals(inbox.getProcessingStatus())
        || !leaseOwner.equals(inbox.getLeaseOwner())
        || !Integer.valueOf(claimedAttempt).equals(inbox.getAttemptCount())) {
      return;
    }

    inbox.setLastErrorCode(truncate(errorCode, 80));
    inbox.setErrorMessage(truncate(errorMessage, 4000));
    inbox.setLeaseOwner(null);
    inbox.setLeaseUntil(null);

    if (!retryable || claimedAttempt >= Math.max(1, maxAttempts)) {
      LocalDateTime now = LocalDateTime.now();
      inbox.setProcessingStatus("DEAD_LETTER");
      inbox.setNextRetryAt(null);
      inbox.setProcessedAt(now);
      inbox.setDeadLetteredAt(now);
    } else {
      inbox.setProcessingStatus("FAILED");
      inbox.setNextRetryAt(nextRetryAt);
      inbox.setProcessedAt(null);
      inbox.setDeadLetteredAt(null);
    }
    inboxRepository.save(inbox);
  }

  private static String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) return value;
    return value.substring(0, maxLength);
  }
}
