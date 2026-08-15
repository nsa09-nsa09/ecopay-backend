package kz.hrms.splitupauth.service;

import java.time.LocalDateTime;
import java.util.List;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import kz.hrms.splitupauth.entity.PeriodType;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.SavedCard;
import kz.hrms.splitupauth.entity.SavedCardStatus;
import kz.hrms.splitupauth.payment.gateway.GatewayChargeRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayChargeResponse;
import kz.hrms.splitupauth.payment.gateway.PaymentGatewayRegistry;
import kz.hrms.splitupauth.repository.PaymentIntentRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.SavedCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecurringChargeService {

  private static final long LEAD_DAYS = 2;
  private static final int MAX_RETRY_COUNT = 3;

  private final RoomMemberRepository roomMemberRepository;
  private final PaymentIntentRepository paymentIntentRepository;
  private final SavedCardRepository savedCardRepository;
  private final PaymentGatewayRegistry gatewayRegistry;
  private final PaymentEventLogger eventLogger;
  private final PaymentService paymentService;

  @Value("${app.recurring.enabled:false}")
  private boolean recurringEnabled;

  /** Runs every day at 03:30 server time. */
  @Scheduled(cron = "0 30 3 * * *")
  public void runDailyAutoCharges() {
    if (!recurringEnabled) {
      log.info("RecurringChargeService: disabled, skipping daily run");
      return;
    }
    log.info("RecurringChargeService: starting daily run");
    List<RoomMember> activeMembers =
        roomMemberRepository.findByStatusAndDeletedAtIsNull(MemberStatus.ACTIVE);
    for (RoomMember member : activeMembers) {
      try {
        tryAutoCharge(member.getId());
      } catch (Exception ex) {
        log.warn("Auto-charge failed for member {}: {}", member.getId(), ex.getMessage());
      }
    }
    log.info("RecurringChargeService: done, scanned {} active members", activeMembers.size());
  }

  @Transactional
  public void tryAutoCharge(Long memberId) {
    if (!recurringEnabled) {
      return;
    }
    RoomMember member = roomMemberRepository.findWithLockById(memberId).orElse(null);
    if (member == null || member.getStatus() != MemberStatus.ACTIVE) {
      return;
    }
    if (member.getUser() == null
        || member.getUser().getDeletedAt() != null
        || member.getUser().getStatus() != kz.hrms.splitupauth.entity.UserStatus.ACTIVE) {
      return;
    }
    if (member.getRoom() == null || member.getRoom().getPeriodType() != PeriodType.MONTHLY) {
      return;
    }

    PaymentIntent lastSuccess =
        paymentIntentRepository
            .findFirstByRoomMemberAndStatusOrderByCreatedAtDesc(member, PaymentIntentStatus.SUCCESS)
            .orElse(null);
    if (lastSuccess == null) {
      return;
    }

    LocalDateTime now = LocalDateTime.now();
    initializeBillingSchedule(member, lastSuccess, now);
    if (now.isBefore(member.getNextBillingAt().minusDays(LEAD_DAYS))) {
      roomMemberRepository.save(member);
      return;
    }
    if (member.getRecurringNextRetryAt() != null
        && now.isBefore(member.getRecurringNextRetryAt())) {
      roomMemberRepository.save(member);
      return;
    }
    if (paymentIntentRepository
        .findFirstByRoomMember_IdAndStatusInOrderByCreatedAtDesc(
            memberId, List.of(PaymentIntentStatus.PENDING, PaymentIntentStatus.UNKNOWN, PaymentIntentStatus.RECONCILING))
        .isPresent()) {
      roomMemberRepository.save(member);
      return;
    }

    SavedCard card =
        savedCardRepository
            .findByUserAndIsDefaultTrueAndStatus(member.getUser(), SavedCardStatus.ACTIVE)
            .orElse(null);
    if (card == null) {
      log.info("Member {} has no default saved card, skipping auto-charge", memberId);
      roomMemberRepository.save(member);
      return;
    }

    int attempt = member.getRecurringRetryCount() == null ? 0 : member.getRecurringRetryCount();
    if (attempt >= MAX_RETRY_COUNT) {
      roomMemberRepository.save(member);
      return;
    }

    String idempotencyKey =
        "recurring-" + memberId + "-" + member.getNextBillingAt().toLocalDate() + "-attempt-" + attempt;
    if (paymentIntentRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
      roomMemberRepository.save(member);
      return;
    }

    var gateway = gatewayRegistry.defaultGateway();
    PaymentIntent intent =
        PaymentIntent.builder()
            .idempotencyKey(idempotencyKey)
            .roomMember(member)
            .user(member.getUser())
            .amount(lastSuccess.getAmount())
            .commissionAmount(lastSuccess.getCommissionAmount())
            .status(PaymentIntentStatus.PENDING)
            .providerName(gateway.providerName())
            .saveCardRequested(false)
            .savedCard(card)
            .expiresAt(now.plusMinutes(30))
            .build();
    intent = paymentIntentRepository.save(intent);

    try {
      GatewayChargeResponse resp =
          gateway.chargeWithToken(
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
                  .build(),
              card.getProviderToken());

      if (resp.isSuccess()) {
        paymentService.finalizeSuccessfulPayment(
            intent.getId(),
            resp.getExternalPaymentId(),
            resp.getProviderStatusCode(),
            null,
            null,
            null,
            null,
            "RECURRING_SUCCESS");
        member.setBillingPeriodStart(member.getNextBillingAt());
        member.setNextBillingAt(member.getNextBillingAt().plusMonths(1));
        member.setRecurringRetryCount(0);
        member.setRecurringNextRetryAt(null);
      } else {
        intent.setStatus(PaymentIntentStatus.FAILED);
        intent.setFailureCode(resp.getFailureCode());
        intent.setFailureMessage(resp.getFailureMessage());
        paymentIntentRepository.save(intent);
        scheduleRetry(member, now);
        if ("EXPIRED_CARD".equalsIgnoreCase(resp.getFailureCode())
            || (resp.getFailureMessage() != null
                && resp.getFailureMessage().toLowerCase().contains("expired"))) {
          card.setStatus(SavedCardStatus.EXPIRED);
          savedCardRepository.save(card);
        }
      }

      roomMemberRepository.save(member);
      eventLogger.log(
          "INTENT",
          intent.getId(),
          resp.isSuccess() ? "RECURRING_SUCCESS" : "RECURRING_FAILED",
          "PENDING",
          resp.isSuccess() ? PaymentIntentStatus.SUCCESS.name() : PaymentIntentStatus.FAILED.name(),
          null,
          null,
          idempotencyKey,
          java.util.Map.of("memberId", String.valueOf(memberId)));
    } catch (Exception ex) {
      log.error("Recurring charge call failed for member {}: {}", memberId, ex.getMessage());
      intent.setStatus(PaymentIntentStatus.UNKNOWN);
      intent.setFailureCode("GATEWAY_INIT_UNKNOWN");
      intent.setFailureMessage(ex.getMessage());
      paymentIntentRepository.save(intent);
      scheduleRetry(member, now);
      roomMemberRepository.save(member);
    }
  }

  private void initializeBillingSchedule(RoomMember member, PaymentIntent lastSuccess, LocalDateTime now) {
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
    int nextRetryCount = (member.getRecurringRetryCount() == null ? 0 : member.getRecurringRetryCount()) + 1;
    member.setRecurringRetryCount(nextRetryCount);
    member.setRecurringNextRetryAt(
        nextRetryCount >= MAX_RETRY_COUNT ? null : now.plusDays(1));
  }
}
