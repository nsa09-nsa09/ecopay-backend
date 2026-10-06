package kz.hrms.splitupauth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import kz.hrms.splitupauth.entity.PeriodType;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.SavedCard;
import kz.hrms.splitupauth.entity.SavedCardStatus;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.payment.gateway.GatewayChargeRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayChargeResponse;
import kz.hrms.splitupauth.payment.gateway.GatewayRequestNotSentException;
import kz.hrms.splitupauth.payment.gateway.PaymentGateway;
import kz.hrms.splitupauth.payment.gateway.PaymentGatewayRegistry;
import kz.hrms.splitupauth.repository.PaymentIntentRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.SavedCardRepository;
import kz.hrms.splitupauth.scheduler.SchedulerLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Monthly auto-renewal with a saved purchase profile.
 *
 * <p><b>Production status:</b> disabled for the MVP launch — {@code ProductionStartupGuard} refuses
 * {@code app.recurring.enabled=true} under the prod profile until FreedomPay confirms the recurring
 * contract (make_recurring_payment semantics, pg_recurring_lifetime limits, callback delivery) for
 * this merchant. The flow below is nevertheless kept correct for dev/stage:
 *
 * <ul>
 *   <li>members are scanned in bounded id-ordered batches, never all at once;
 *   <li>each attempt has a deterministic idempotency key per billing period and attempt number;
 *   <li>the provider call happens OUTSIDE any database transaction;
 *   <li>a synchronous provider "ok" is acceptance, not captured money: the intent stays PENDING and
 *       is settled by the signed result callback or by status reconciliation, and the billing
 *       period only advances once a captured payment exists;
 *   <li>an ambiguous answer (timeout/unsigned) leaves the intent UNKNOWN for reconciliation and is
 *       never retried blindly; an open intent blocks any further attempt for that member;
 *   <li>retries are capped ({@value #MAX_RETRY_COUNT}) and spaced one day apart.
 * </ul>
 */
@Service
@Slf4j
public class RecurringChargeService {

  private static final long LEAD_DAYS = 2;
  static final int MAX_RETRY_COUNT = 3;
  private static final int BATCH_SIZE = 200;

  private static final List<PaymentIntentStatus> OPEN_STATUSES =
      List.of(
          PaymentIntentStatus.PENDING,
          PaymentIntentStatus.UNKNOWN,
          PaymentIntentStatus.RECONCILING);

  private static final List<PaymentIntentStatus> CAPTURED_STATUSES =
      List.of(
          PaymentIntentStatus.SUCCESS,
          PaymentIntentStatus.REFUND_REQUIRED,
          PaymentIntentStatus.REFUND_PENDING,
          PaymentIntentStatus.REFUNDED,
          PaymentIntentStatus.REQUIRES_REVIEW,
          PaymentIntentStatus.CAPTURE_ANOMALY);

  private final RoomMemberRepository roomMemberRepository;
  private final PaymentIntentRepository paymentIntentRepository;
  private final SavedCardRepository savedCardRepository;
  private final PaymentGatewayRegistry gatewayRegistry;
  private final PaymentEventLogger eventLogger;
  private final PaymentService paymentService;
  private final LiveMoneyGuard liveMoneyGuard;
  private final SchedulerLock schedulerLock;
  private final Clock clock;
  private final TransactionTemplate tx;

  @Value("${app.recurring.enabled:false}")
  private boolean recurringEnabled;

  public RecurringChargeService(
      RoomMemberRepository roomMemberRepository,
      PaymentIntentRepository paymentIntentRepository,
      SavedCardRepository savedCardRepository,
      PaymentGatewayRegistry gatewayRegistry,
      PaymentEventLogger eventLogger,
      PaymentService paymentService,
      LiveMoneyGuard liveMoneyGuard,
      SchedulerLock schedulerLock,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.roomMemberRepository = roomMemberRepository;
    this.paymentIntentRepository = paymentIntentRepository;
    this.savedCardRepository = savedCardRepository;
    this.gatewayRegistry = gatewayRegistry;
    this.eventLogger = eventLogger;
    this.paymentService = paymentService;
    this.liveMoneyGuard = liveMoneyGuard;
    this.schedulerLock = schedulerLock;
    this.clock = clock;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /** Runs every day at 03:30 server time, on one replica at a time. */
  @Scheduled(cron = "0 30 3 * * *")
  public void runDailyAutoCharges() {
    if (!recurringEnabled) {
      log.info("RecurringChargeService: disabled, skipping daily run");
      return;
    }
    schedulerLock.runExclusive("recurring-charges", Duration.ofHours(3), this::runAllBatches);
  }

  /** Scans ACTIVE members in id order, {@value #BATCH_SIZE} at a time. */
  public int runAllBatches() {
    long lastId = 0;
    int scanned = 0;
    while (true) {
      long cursor = lastId;
      List<Long> ids =
          tx.execute(
              status ->
                  roomMemberRepository.findActiveIdsAfter(
                      MemberStatus.ACTIVE, cursor, PageRequest.of(0, BATCH_SIZE)));
      if (ids == null || ids.isEmpty()) {
        break;
      }
      for (Long memberId : ids) {
        try {
          tryAutoCharge(memberId);
        } catch (RuntimeException ex) {
          log.warn("Auto-charge failed for member {}: {}", memberId, ex.getClass().getSimpleName());
        }
      }
      scanned += ids.size();
      lastId = ids.get(ids.size() - 1);
    }
    log.info("RecurringChargeService: done, scanned {} active members", scanned);
    return scanned;
  }

  /**
   * One member: decide and record the attempt (transaction 1), call the provider without holding
   * any lock, then record the provider's answer (transaction 2).
   */
  public void tryAutoCharge(Long memberId) {
    if (!recurringEnabled) {
      return;
    }
    try {
      liveMoneyGuard.requireEnabledForNewCharge();
    } catch (RuntimeException disabled) {
      return;
    }
    Attempt attempt = tx.execute(status -> prepareAttempt(memberId));
    if (attempt == null) {
      return;
    }

    GatewayChargeResponse resp;
    try {
      resp = attempt.gateway().chargeWithToken(attempt.request(), attempt.cardToken());
    } catch (GatewayRequestNotSentException ex) {
      tx.executeWithoutResult(status -> recordNotSent(attempt, ex));
      return;
    } catch (RuntimeException ex) {
      tx.executeWithoutResult(status -> recordAmbiguous(attempt, ex));
      return;
    }
    tx.executeWithoutResult(status -> recordResponse(attempt, resp));
  }

  private Attempt prepareAttempt(Long memberId) {
    RoomMember member = roomMemberRepository.findWithLockById(memberId).orElse(null);
    if (member == null
        || member.getStatus() != MemberStatus.ACTIVE
        || member.getDeletedAt() != null
        || member.getUser() == null
        || member.getUser().getDeletedAt() != null
        || member.getUser().getStatus() != UserStatus.ACTIVE
        || member.getRoom() == null
        || member.getRoom().getPeriodType() != PeriodType.MONTHLY) {
      return null;
    }

    PaymentIntent lastSuccess =
        paymentIntentRepository
            .findFirstByRoomMemberAndStatusOrderByCreatedAtDesc(member, PaymentIntentStatus.SUCCESS)
            .orElse(null);
    if (lastSuccess == null) {
      return null;
    }

    LocalDateTime now = LocalDateTime.now(clock);
    initializeBillingSchedule(member, lastSuccess, now);

    // Settle the current period first: a captured attempt advances it, a definitively failed one
    // consumes a retry. An open attempt blocks everything until the provider answers.
    int attemptNo = member.getRecurringRetryCount() == null ? 0 : member.getRecurringRetryCount();
    PaymentIntent current =
        paymentIntentRepository
            .findByIdempotencyKey(idempotencyKey(member, attemptNo))
            .orElse(null);
    if (current != null) {
      if (CAPTURED_STATUSES.contains(current.getStatus())) {
        advancePeriod(member);
        roomMemberRepository.save(member);
        return null;
      }
      if (OPEN_STATUSES.contains(current.getStatus())) {
        roomMemberRepository.save(member);
        return null;
      }
      scheduleRetry(member, now);
      roomMemberRepository.save(member);
      return null;
    }

    if (now.isBefore(member.getNextBillingAt().minusDays(LEAD_DAYS))
        || (member.getRecurringNextRetryAt() != null
            && now.isBefore(member.getRecurringNextRetryAt()))
        || attemptNo >= MAX_RETRY_COUNT
        || paymentIntentRepository
            .findFirstByRoomMember_IdAndStatusInOrderByCreatedAtDesc(memberId, OPEN_STATUSES)
            .isPresent()) {
      roomMemberRepository.save(member);
      return null;
    }

    SavedCard card =
        savedCardRepository
            .findByUserAndIsDefaultTrueAndStatus(member.getUser(), SavedCardStatus.ACTIVE)
            .orElse(null);
    if (card == null) {
      log.info("Member {} has no default saved card, skipping auto-charge", memberId);
      roomMemberRepository.save(member);
      return null;
    }

    PaymentGateway gateway = gatewayRegistry.defaultGateway();
    PaymentIntent intent =
        paymentIntentRepository.save(
            PaymentIntent.builder()
                .idempotencyKey(idempotencyKey(member, attemptNo))
                .roomMember(member)
                .user(member.getUser())
                .amount(lastSuccess.getAmount())
                .commissionAmount(lastSuccess.getCommissionAmount())
                .status(PaymentIntentStatus.PENDING)
                .providerName(gateway.providerName())
                .saveCardRequested(false)
                .savedCard(card)
                .expiresAt(now.plusMinutes(30))
                .build());
    roomMemberRepository.save(member);

    GatewayChargeRequest request =
        GatewayChargeRequest.builder()
            .intentId(intent.getId())
            .roomMemberId(member.getId())
            .roomId(member.getRoom().getId())
            .idempotencyKey(intent.getIdempotencyKey())
            .amount(intent.getAmount())
            .currency("KZT")
            .description("EcoPay recurring " + member.getRoom().getTitle())
            .userEmail(member.getUser().getEmail())
            .userPhone(member.getUser().getPhone())
            .build();
    return new Attempt(
        memberId, intent.getId(), card.getId(), card.getProviderToken(), gateway, request);
  }

  private void recordResponse(Attempt attempt, GatewayChargeResponse resp) {
    PaymentIntent intent =
        paymentIntentRepository.findWithLockById(attempt.intentId()).orElse(null);
    RoomMember member = roomMemberRepository.findWithLockById(attempt.memberId()).orElse(null);
    if (intent == null || member == null) {
      return;
    }
    String event;
    if (resp.isSuccess() && resp.isCaptureConfirmed()) {
      paymentService.finalizeSuccessfulPayment(
          intent.getId(),
          resp.getExternalPaymentId(),
          resp.getProviderStatusCode(),
          null,
          null,
          null,
          null,
          "RECURRING_SUCCESS");
      advancePeriod(member);
      event = "RECURRING_SUCCESS";
    } else if (resp.isSuccess()) {
      // Accepted, not yet captured: wait for the callback / status reconciliation.
      if (intent.getStatus() == PaymentIntentStatus.PENDING) {
        intent.setExternalPaymentId(resp.getExternalPaymentId());
        intent.setProviderStatusCode(resp.getProviderStatusCode());
        paymentIntentRepository.save(intent);
      }
      event = "RECURRING_ACCEPTED";
    } else {
      intent.setStatus(PaymentIntentStatus.FAILED);
      intent.setFailureCode(resp.getFailureCode());
      intent.setFailureMessage(resp.getFailureMessage());
      paymentIntentRepository.save(intent);
      scheduleRetry(member, LocalDateTime.now(clock));
      if ("EXPIRED_CARD".equalsIgnoreCase(resp.getFailureCode())
          || (resp.getFailureMessage() != null
              && resp.getFailureMessage().toLowerCase().contains("expired"))) {
        savedCardRepository
            .findById(attempt.cardId())
            .ifPresent(
                card -> {
                  card.setStatus(SavedCardStatus.EXPIRED);
                  savedCardRepository.save(card);
                });
      }
      event = "RECURRING_FAILED";
    }
    roomMemberRepository.save(member);
    eventLogger.log(
        "INTENT",
        intent.getId(),
        event,
        "PENDING",
        intent.getStatus().name(),
        null,
        null,
        intent.getIdempotencyKey(),
        Map.of("memberId", String.valueOf(attempt.memberId())));
  }

  private void recordNotSent(Attempt attempt, RuntimeException ex) {
    PaymentIntent intent =
        paymentIntentRepository.findWithLockById(attempt.intentId()).orElse(null);
    RoomMember member = roomMemberRepository.findWithLockById(attempt.memberId()).orElse(null);
    if (intent == null || member == null) {
      return;
    }
    intent.setStatus(PaymentIntentStatus.FAILED);
    intent.setFailureCode("GATEWAY_NOT_SENT");
    intent.setFailureMessage(ex.getClass().getSimpleName());
    paymentIntentRepository.save(intent);
    scheduleRetry(member, LocalDateTime.now(clock));
    roomMemberRepository.save(member);
  }

  private void recordAmbiguous(Attempt attempt, RuntimeException ex) {
    log.error(
        "Recurring charge for member {} has an ambiguous outcome: {}",
        attempt.memberId(),
        ex.getClass().getSimpleName());
    PaymentIntent intent =
        paymentIntentRepository.findWithLockById(attempt.intentId()).orElse(null);
    if (intent == null || intent.getStatus() != PaymentIntentStatus.PENDING) {
      return;
    }
    intent.setStatus(PaymentIntentStatus.UNKNOWN);
    intent.setFailureCode("GATEWAY_INIT_UNKNOWN");
    intent.setFailureMessage(ex.getClass().getSimpleName());
    paymentIntentRepository.save(intent);
  }

  private static String idempotencyKey(RoomMember member, int attempt) {
    return "recurring-"
        + member.getId()
        + "-"
        + member.getNextBillingAt().toLocalDate()
        + "-attempt-"
        + attempt;
  }

  private static void advancePeriod(RoomMember member) {
    member.setBillingPeriodStart(member.getNextBillingAt());
    member.setNextBillingAt(member.getNextBillingAt().plusMonths(1));
    member.setRecurringRetryCount(0);
    member.setRecurringNextRetryAt(null);
  }

  private void initializeBillingSchedule(
      RoomMember member, PaymentIntent lastSuccess, LocalDateTime now) {
    LocalDateTime anchor = member.getBillingAnchorAt();
    if (anchor == null) {
      anchor = lastSuccess.getCreatedAt() == null ? now : lastSuccess.getCreatedAt();
      member.setBillingAnchorAt(anchor);
    }
    if (member.getBillingPeriodStart() == null) {
      member.setBillingPeriodStart(anchor);
    }
    if (member.getNextBillingAt() == null) {
      member.setNextBillingAt(member.getBillingPeriodStart().plusMonths(1));
    }
    if (member.getRecurringRetryCount() == null) {
      member.setRecurringRetryCount(0);
    }
  }

  private void scheduleRetry(RoomMember member, LocalDateTime now) {
    int nextRetryCount =
        (member.getRecurringRetryCount() == null ? 0 : member.getRecurringRetryCount()) + 1;
    member.setRecurringRetryCount(nextRetryCount);
    member.setRecurringNextRetryAt(nextRetryCount >= MAX_RETRY_COUNT ? null : now.plusDays(1));
  }

  private record Attempt(
      Long memberId,
      Long intentId,
      Long cardId,
      String cardToken,
      PaymentGateway gateway,
      GatewayChargeRequest request) {}
}
