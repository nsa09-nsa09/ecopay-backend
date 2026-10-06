package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import kz.hrms.splitupauth.dto.ConfirmPaymentRequest;
import kz.hrms.splitupauth.dto.CreatePaymentIntentRequest;
import kz.hrms.splitupauth.dto.PaymentIntentResponse;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceConflictException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.payment.gateway.GatewayChargeRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayChargeResponse;
import kz.hrms.splitupauth.payment.gateway.GatewayStatusResponse;
import kz.hrms.splitupauth.payment.gateway.GatewayWebhookEvent;
import kz.hrms.splitupauth.payment.gateway.PaymentGateway;
import kz.hrms.splitupauth.payment.gateway.PaymentGatewayRegistry;
import kz.hrms.splitupauth.payment.gateway.ProviderPaymentState;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway;
import kz.hrms.splitupauth.repository.PaymentIntentRepository;
import kz.hrms.splitupauth.repository.PaymentReservationRepository;
import kz.hrms.splitupauth.repository.PaymentTransactionRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.SavedCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

  private static final int MONEY_SCALE = 2;
  private static final int MAX_RECONCILE_ATTEMPTS = 50;
  private static final long PAYMENT_INTENT_TTL_MINUTES = 30;
  private static final List<PaymentIntentStatus> OPEN_INTENT_STATUSES =
      List.of(
          PaymentIntentStatus.PENDING,
          PaymentIntentStatus.UNKNOWN,
          PaymentIntentStatus.RECONCILING);
  private static final List<PaymentIntentStatus> BLOCKING_INTENT_STATUSES =
      List.of(
          PaymentIntentStatus.PENDING,
          PaymentIntentStatus.UNKNOWN,
          PaymentIntentStatus.RECONCILING,
          PaymentIntentStatus.CAPTURE_ANOMALY);
  private static final List<PaymentIntentStatus> PROVIDER_CAPTURED_STATUSES =
      List.of(
          PaymentIntentStatus.SUCCESS,
          PaymentIntentStatus.REFUND_REQUIRED,
          PaymentIntentStatus.REFUND_PENDING,
          PaymentIntentStatus.REFUNDED,
          PaymentIntentStatus.REQUIRES_REVIEW,
          PaymentIntentStatus.CAPTURE_ANOMALY);

  private final PaymentIntentRepository paymentIntentRepository;
  private final PaymentReservationRepository paymentReservationRepository;
  private final PaymentTransactionRepository paymentTransactionRepository;
  private final RoomMemberRepository roomMemberRepository;
  private final RoomRepository roomRepository;
  private final SavedCardRepository savedCardRepository;
  private final RoomMemberService roomMemberService;
  private final PaymentGatewayRegistry gatewayRegistry;
  private final SavedCardService savedCardService;
  private final PaymentEventLogger eventLogger;
  private final PayoutService payoutService;
  private final RefundService refundService;
  private final RoomEventLogger roomEventLogger;
  private final NotificationService notificationService;
  private final CommissionCalculator commissionCalculator;
  private final MoneyLedgerService moneyLedgerService;
  private final LiveMoneyGuard liveMoneyGuard;
  private final Clock clock;
  private final PlatformTransactionManager transactionManager;

  public PaymentIntentResponse createPaymentIntent(
      Long roomMemberId, User currentUser, CreatePaymentIntentRequest request) {
    liveMoneyGuard.requireEnabledForNewCharge();
    PreparedPayment prepared;
    try {
      prepared =
          tx().execute(status -> createIntentAndReservation(roomMemberId, currentUser, request));
    } catch (DataIntegrityViolationException ex) {
      PaymentIntentResponse existingResponse =
          tx().execute(
                  status ->
                      paymentIntentRepository
                          .findByIdempotencyKey(request.getIdempotencyKey())
                          .map(
                              existing ->
                                  mapToResponse(
                                      requireSameIdempotentRequest(
                                          existing, roomMemberId, currentUser, request)))
                          .orElse(null));
      if (existingResponse != null) {
        return existingResponse;
      }
      PaymentIntentResponse openResponse =
          tx().execute(
                  status -> {
                    PaymentIntent openIntent = findOpenIntentForMember(roomMemberId, currentUser);
                    return openIntent == null ? null : mapToResponse(openIntent);
                  });
      if (openResponse != null) {
        return openResponse;
      }
      throw ex;
    }

    PaymentIntent intent = prepared.intent();
    if (intent.getStatus() != PaymentIntentStatus.PENDING || prepared.chargeRequest() == null) {
      return prepared.response() != null ? prepared.response() : mapToResponse(intent);
    }

    GatewayChargeResponse chargeResp;
    try {
      PaymentGateway gateway = gatewayRegistry.resolve(intent.getProviderName());
      chargeResp =
          prepared.savedCardToken() != null
              ? gateway.chargeWithToken(prepared.chargeRequest(), prepared.savedCardToken())
              : gateway.initCharge(prepared.chargeRequest());
    } catch (Exception ex) {
      log.error(
          "Gateway charge initiation failed for intent {}: {}",
          intent.getId(),
          ex.getClass().getSimpleName());
      MoneyOperationsMetrics.paymentInit("unknown");
      PaymentIntentResponse failed =
          tx().execute(
                  status ->
                      mapToResponse(
                          markIntentUnknownAfterGatewayException(
                              intent.getId(), currentUser.getId(), ex.getMessage())));
      return failed;
    }

    PaymentIntentResponse updated =
        tx().execute(
                status ->
                    mapToResponse(
                        applyGatewayInitResponse(intent.getId(), chargeResp, currentUser.getId())));
    MoneyOperationsMetrics.paymentInit(
        !chargeResp.isSuccess()
            ? "failed"
            : chargeResp.isRequiresRedirect()
                ? "redirect"
                : chargeResp.isCaptureConfirmed() ? "captured" : "accepted");

    // Only a gateway that PROVES synchronous capture (the in-memory mock) finalizes here. A
    // provider "ok" for a saved-card/recurring charge is acceptance: the intent stays open until
    // the signed result callback or a status query confirms captured money.
    if (chargeResp.isSuccess()
        && !chargeResp.isRequiresRedirect()
        && chargeResp.isCaptureConfirmed()) {
      Long updatedIntentId = updated.getId();
      updated =
          tx().execute(
                  status ->
                      mapToResponse(
                          finalizeSuccessfulPayment(
                              updatedIntentId,
                              chargeResp.getExternalPaymentId(),
                              chargeResp.getProviderStatusCode(),
                              null,
                              null,
                              null,
                              currentUser.getId(),
                              "GATEWAY_SYNC_SUCCESS")));
    }

    return updated;
  }

  private PreparedPayment createIntentAndReservation(
      Long roomMemberId, User currentUser, CreatePaymentIntentRequest request) {
    RoomMember roomMember =
        roomMemberRepository
            .findById(roomMemberId)
            .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));

    if (roomMember.getDeletedAt() != null) {
      throw new ResourceNotFoundException("Membership not found");
    }

    if (!roomMember.getUser().getId().equals(currentUser.getId())) {
      throw new ForbiddenOperationException(
          "You can only create payment intent for your own membership");
    }

    // Idempotency must be checked BEFORE the status guard: once a payment succeeds the
    // membership leaves APPLIED, and a retried call with the same key must still return
    // the original intent (not fail the status check → no double charge, true idempotency).
    PaymentIntent existing =
        paymentIntentRepository.findByIdempotencyKey(request.getIdempotencyKey()).orElse(null);
    if (existing != null) {
      existing = requireSameIdempotentRequest(existing, roomMemberId, currentUser, request);
      return PreparedPayment.existing(existing, mapToResponse(existing));
    }

    existing = findOpenIntentForMember(roomMemberId, currentUser);
    if (existing != null) {
      return PreparedPayment.existing(existing, mapToResponse(existing));
    }

    if (roomMember.getStatus() != MemberStatus.APPLIED) {
      throw new InvalidRequestException(
          "Payment intent can only be created for APPLIED membership");
    }

    SavedCard savedCard = null;
    String savedCardToken = null;
    if (request.getSavedCardId() != null) {
      savedCard =
          savedCardRepository
              .findById(request.getSavedCardId())
              .filter(c -> c.getUser().getId().equals(currentUser.getId()))
              .filter(c -> c.getStatus() == SavedCardStatus.ACTIVE)
              .orElseThrow(() -> new InvalidRequestException("Saved card not found or inactive"));
      savedCardToken = savedCard.getProviderToken();
    }

    Room lockedRoom =
        roomRepository
            .findByIdForUpdate(roomMember.getRoom().getId())
            .orElseThrow(() -> new ResourceNotFoundException("Room not found"));
    roomMember.setRoom(lockedRoom);
    ensurePaymentAllowedUnderRoomLock(lockedRoom, roomMember, currentUser);

    existing = findOpenIntentForMember(roomMemberId, currentUser);
    if (existing != null) {
      return PreparedPayment.existing(existing, mapToResponse(existing));
    }

    PaymentGateway gateway = gatewayRegistry.defaultGateway();
    BigDecimal share = resolveShareAmount(lockedRoom);
    BigDecimal commission =
        commissionCalculator.commissionFor(share, RoomSeatMath.existingMembersCount(lockedRoom));
    BigDecimal amount = share.add(commission);

    long paidSeats =
        roomMemberRepository.countByRoomAndStatusInAndDeletedAtIsNull(
            lockedRoom, List.of(MemberStatus.PENDING, MemberStatus.ACTIVE));
    LocalDateTime now = LocalDateTime.now();
    long activeReservations =
        paymentReservationRepository.countByRoomAndStatusAndExpiresAtAfter(
            lockedRoom, PaymentReservationStatus.RESERVED, now);
    if (paidSeats + activeReservations >= RoomSeatMath.marketplaceCapacity(lockedRoom)) {
      throw new ResourceConflictException("ROOM_FULL", "Room is full");
    }

    PaymentIntent intent =
        PaymentIntent.builder()
            .idempotencyKey(request.getIdempotencyKey())
            .roomMember(roomMember)
            .user(currentUser)
            .amount(amount)
            .commissionAmount(commission)
            .status(PaymentIntentStatus.PENDING)
            .providerName(gateway.providerName())
            .saveCardRequested(Boolean.TRUE.equals(request.getSaveCard()))
            .savedCard(savedCard)
            .expiresAt(now.plusMinutes(PAYMENT_INTENT_TTL_MINUTES))
            .build();
    intent = paymentIntentRepository.save(intent);
    paymentReservationRepository.save(
        PaymentReservation.builder()
            .paymentIntent(intent)
            .roomMember(roomMember)
            .room(lockedRoom)
            .status(PaymentReservationStatus.RESERVED)
            .expiresAt(intent.getExpiresAt())
            .build());

    eventLogger.log(
        "INTENT",
        intent.getId(),
        "CREATED",
        null,
        intent.getStatus().name(),
        currentUser.getId(),
        null,
        intent.getIdempotencyKey(),
        Map.of(
            "provider",
            gateway.providerName(),
            "amount",
            amount.toPlainString(),
            "share",
            share.toPlainString(),
            "commission",
            commission.toPlainString()));

    GatewayChargeRequest chargeReq =
        GatewayChargeRequest.builder()
            .intentId(intent.getId())
            .roomMemberId(roomMember.getId())
            .roomId(lockedRoom.getId())
            .idempotencyKey(intent.getIdempotencyKey())
            .amount(amount)
            .currency("KZT")
            .description("EcoPay membership #" + roomMember.getId())
            .userEmail(currentUser.getEmail())
            .userPhone(currentUser.getPhone())
            .userId(currentUser.getId() == null ? null : String.valueOf(currentUser.getId()))
            .saveCardRequested(intent.getSaveCardRequested())
            .build();
    return new PreparedPayment(intent, savedCardToken, chargeReq, null);
  }

  private PaymentIntent applyGatewayInitResponse(
      Long intentId, GatewayChargeResponse chargeResp, Long actorUserId) {
    PaymentIntent intent =
        paymentIntentRepository
            .findWithLockById(intentId)
            .orElseThrow(() -> new ResourceNotFoundException("Payment intent not found"));
    if (!chargeResp.isSuccess()) {
      intent.setStatus(PaymentIntentStatus.FAILED);
      intent.setProviderStatusCode(chargeResp.getProviderStatusCode());
      intent.setFailureCode(chargeResp.getFailureCode());
      intent.setFailureMessage(chargeResp.getFailureMessage());
      releaseReservation(intent, "GATEWAY_FAILED");
    } else {
      intent.setExternalPaymentId(chargeResp.getExternalPaymentId());
      intent.setPaymentUrl(chargeResp.getPaymentUrl());
      intent.setProviderStatusCode(chargeResp.getProviderStatusCode());
    }
    intent = paymentIntentRepository.save(intent);
    return intent;
  }

  /**
   * Single source of truth for the side effects of a successful charge: record the transaction,
   * advance the membership (idempotent), and create the owner payout. Used by the initial intent
   * flow, the webhook flow, and recurring auto-charges.
   */
  @Transactional
  public void applySuccessfulCharge(
      PaymentIntent intent, String cardPanMask, String providerSignature) {
    if (intent == null || intent.getId() == null) {
      return;
    }
    if (intent.getId() != null) {
      finalizeSuccessfulPayment(
          intent.getId(),
          intent.getExternalPaymentId(),
          intent.getProviderStatusCode(),
          cardPanMask,
          providerSignature,
          null,
          null,
          "DIRECT_SUCCESS");
      return;
    }
    recordSuccessTransaction(intent, cardPanMask, providerSignature, true);
    roomMemberService.markMembershipAsPaid(intent.getRoomMember());
    payoutService.createOwnerPayoutForSuccessfulPayment(intent);

    RoomMember member = intent.getRoomMember();
    roomEventLogger.log(
        member == null ? null : member.getRoom(),
        member,
        intent.getUser(),
        "MEMBER",
        "payment_success",
        Map.of(
            "intentId",
            String.valueOf(intent.getId()),
            "amount",
            String.valueOf(intent.getAmount())));

    // Notify the payer that the charge succeeded. Single point covers the
    // synchronous, redirect-reconcile, and webhook success paths.
    Room room = member == null ? null : member.getRoom();
    notificationService.notify(
        intent.getUser(),
        NotificationType.PAYMENT_SUCCESS,
        "Оплата подтверждена",
        "Оплата"
            + (room == null ? "" : " за участие в комнате «" + room.getTitle() + "»")
            + " на сумму "
            + intent.getAmount()
            + (room == null ? "" : " " + room.getCurrency())
            + " прошла успешно.",
        room == null ? null : "/rooms/member/" + room.getId(),
        Map.of("intentId", intent.getId(), "roomId", room == null ? 0L : room.getId()));
  }

  /**
   * Fails PENDING intents whose 30-minute window elapsed without a terminal callback. Prevents
   * stale intents from lingering forever (the user can then safely retry). Returns the number of
   * intents expired.
   */
  @Transactional
  public int expireStalePendingIntents() {
    LocalDateTime now = LocalDateTime.now();
    List<PaymentIntent> candidates =
        OPEN_INTENT_STATUSES.stream()
            .flatMap(
                status ->
                    paymentIntentRepository.findByStatusAndExpiresAtBefore(status, now).stream())
            .toList();
    List<PaymentIntent> stale = new java.util.ArrayList<>();
    for (PaymentIntent candidate : candidates) {
      // Re-read under a row lock: a webhook/redirect may have finalized it since the scan, and an
      // unlocked save would overwrite SUCCESS with EXPIRED.
      PaymentIntent intent =
          paymentIntentRepository.findWithLockById(candidate.getId()).orElse(null);
      if (intent == null
          || !OPEN_INTENT_STATUSES.contains(intent.getStatus())
          || intent.getExpiresAt() == null
          || !intent.getExpiresAt().isBefore(now)) {
        continue;
      }
      stale.add(intent);
      String fromStatus = intent.getStatus().name();
      intent.setStatus(PaymentIntentStatus.EXPIRED);
      intent.setFailureCode("EXPIRED");
      intent.setFailureMessage("Payment was not completed before the intent expired");
      releaseReservation(intent, "INTENT_EXPIRED");
      paymentIntentRepository.save(intent);
      eventLogger.log(
          "INTENT",
          intent.getId(),
          "EXPIRED",
          fromStatus,
          "EXPIRED",
          null,
          null,
          intent.getIdempotencyKey(),
          Map.of("expiresAt", String.valueOf(intent.getExpiresAt())));
    }
    if (!stale.isEmpty()) {
      log.info("Expired {} stale open payment intents", stale.size());
    }
    return stale.size();
  }

  @Transactional(readOnly = true)
  public PaymentIntentResponse getPaymentIntent(Long intentId, User currentUser) {
    PaymentIntent intent =
        paymentIntentRepository
            .findById(intentId)
            .orElseThrow(() -> new ResourceNotFoundException("Payment intent not found"));
    if (!intent.getUser().getId().equals(currentUser.getId())) {
      throw new ForbiddenOperationException("Not your payment intent");
    }
    return mapToResponse(intent);
  }

  @Transactional(readOnly = true)
  public PaymentIntentResponse getCurrentPaymentIntentForMember(
      Long roomMemberId, User currentUser) {
    PaymentIntent intent = findOpenIntentForMember(roomMemberId, currentUser);
    if (intent == null) {
      throw new ResourceNotFoundException("Open payment intent not found");
    }
    return mapToResponse(intent);
  }

  /**
   * Browser-return reconciliation. The redirect itself proves nothing (the success URL can be
   * opened by anyone); this asks the provider for the payment state and applies only a signed,
   * amount- and currency-consistent CAPTURED answer. The intent row lock is NOT held during the
   * provider call; the result is re-validated under the lock before anything changes.
   */
  public PaymentIntentResponse confirmPaymentSuccess(
      Long paymentIntentId, User currentUser, ConfirmPaymentRequest request) {
    ReconcileTarget target =
        tx().execute(
                status -> {
                  PaymentIntent intent =
                      paymentIntentRepository
                          .findById(paymentIntentId)
                          .orElseThrow(
                              () -> new ResourceNotFoundException("Payment intent not found"));
                  if (!intent.getUser().getId().equals(currentUser.getId())) {
                    throw new ForbiddenOperationException("Not your payment intent");
                  }
                  return new ReconcileTarget(
                      intent.getId(),
                      intent.getProviderName(),
                      intent.getExternalPaymentId(),
                      isReconcilable(intent),
                      mapToResponse(intent));
                });
    if (!target.reconcilable()) {
      return target.response();
    }
    return reconcileWithProvider(target, currentUser.getId(), "REDIRECT_RECONCILE");
  }

  /**
   * Scheduled reconciliation of intents whose outcome is still open at the provider side (lost
   * callbacks, ambiguous initiation, accepted-but-unconfirmed recurring charges). Bounded batch,
   * spaced provider calls (FreedomPay recommends 1.5-2 s between payment API requests), capped
   * attempts per intent. Returns the number of intents queried.
   */
  public int reconcileOpenIntents(int limit, long spacingMillis) {
    LocalDateTime now = LocalDateTime.now(clock);
    List<Long> ids =
        tx().execute(
                status ->
                    paymentIntentRepository.findIdsForProviderReconciliation(
                        OPEN_INTENT_STATUSES,
                        FreedomPayGateway.PROVIDER_NAME,
                        now.minusMinutes(2),
                        now.minusMinutes(5),
                        MAX_RECONCILE_ATTEMPTS,
                        org.springframework.data.domain.PageRequest.of(0, Math.max(1, limit))));
    int queried = 0;
    for (Long id : ids) {
      ReconcileTarget target =
          tx().execute(
                  status -> {
                    PaymentIntent intent = paymentIntentRepository.findById(id).orElse(null);
                    if (intent == null || !isReconcilable(intent)) {
                      return null;
                    }
                    intent.setLastReconciledAt(LocalDateTime.now(clock));
                    intent.setReconcileAttempts(
                        (intent.getReconcileAttempts() == null ? 0 : intent.getReconcileAttempts())
                            + 1);
                    paymentIntentRepository.save(intent);
                    return new ReconcileTarget(
                        intent.getId(),
                        intent.getProviderName(),
                        intent.getExternalPaymentId(),
                        true,
                        null);
                  });
      if (target == null) {
        continue;
      }
      if (queried > 0 && spacingMillis > 0) {
        try {
          Thread.sleep(spacingMillis);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      queried++;
      try {
        reconcileWithProvider(target, null, "SCHEDULED_RECONCILE");
      } catch (RuntimeException ex) {
        log.warn(
            "Scheduled reconciliation of intent {} failed: {}", id, ex.getClass().getSimpleName());
      }
    }
    return queried;
  }

  private boolean isReconcilable(PaymentIntent intent) {
    if (PROVIDER_CAPTURED_STATUSES.contains(intent.getStatus())) {
      return false;
    }
    return OPEN_INTENT_STATUSES.contains(intent.getStatus())
        || intent.getStatus() == PaymentIntentStatus.EXPIRED
        || intent.getStatus() == PaymentIntentStatus.FAILED;
  }

  private PaymentIntentResponse reconcileWithProvider(
      ReconcileTarget target, Long actorUserId, String eventPrefix) {
    PaymentGateway gateway = gatewayRegistry.resolve(target.providerName());
    GatewayStatusResponse providerStatus;
    try {
      if (target.externalPaymentId() != null && !target.externalPaymentId().isBlank()) {
        providerStatus = gateway.getStatus(target.externalPaymentId());
      } else {
        // Ambiguous initiation: no provider id was stored, look the payment up by our order id.
        providerStatus = gateway.getStatusByOrderId(String.valueOf(target.intentId()));
        if (providerStatus == null) {
          return tx().execute(
                  s ->
                      mapToResponse(
                          paymentIntentRepository.findById(target.intentId()).orElseThrow()));
        }
      }
    } catch (Exception ex) {
      log.warn(
          "Status reconcile failed for intent {}: {}",
          target.intentId(),
          ex.getClass().getSimpleName());
      MoneyOperationsMetrics.reconciliation(
          eventPrefix.startsWith("SCHEDULED") ? "scheduled" : "redirect", "error");
      return tx().execute(
              s ->
                  mapToResponse(
                      markReconcileUnknown(target.intentId(), actorUserId, eventPrefix, ex)));
    }
    MoneyOperationsMetrics.reconciliation(
        eventPrefix.startsWith("SCHEDULED") ? "scheduled" : "redirect",
        providerStatus.isNotFound()
            ? "not_found"
            : String.valueOf(providerStatus.getStatus()).toLowerCase(java.util.Locale.ROOT));
    GatewayStatusResponse finalStatus = providerStatus;
    return tx().execute(
            s ->
                mapToResponse(
                    applyProviderStatus(target.intentId(), finalStatus, actorUserId, eventPrefix)));
  }

  private PaymentIntent markReconcileUnknown(
      Long intentId, Long actorUserId, String eventPrefix, Exception ex) {
    PaymentIntent intent =
        paymentIntentRepository
            .findWithLockById(intentId)
            .orElseThrow(() -> new ResourceNotFoundException("Payment intent not found"));
    if (!OPEN_INTENT_STATUSES.contains(intent.getStatus())) {
      return intent;
    }
    String fromStatus = intent.getStatus().name();
    intent.setStatus(PaymentIntentStatus.RECONCILING);
    intent.setFailureCode("GATEWAY_STATUS_UNKNOWN");
    intent.setFailureMessage("Gateway status check failed: " + ex.getClass().getSimpleName());
    intent = paymentIntentRepository.save(intent);
    eventLogger.log(
        "INTENT",
        intent.getId(),
        eventPrefix + "_UNKNOWN",
        fromStatus,
        intent.getStatus().name(),
        actorUserId,
        null,
        intent.getIdempotencyKey(),
        Map.of("error", ex.getClass().getSimpleName()));
    return intent;
  }

  /** Applies a provider status answer under the intent lock, re-checking the current state. */
  private PaymentIntent applyProviderStatus(
      Long intentId, GatewayStatusResponse status, Long actorUserId, String eventPrefix) {
    PaymentIntent intent =
        paymentIntentRepository
            .findWithLockById(intentId)
            .orElseThrow(() -> new ResourceNotFoundException("Payment intent not found"));
    if (!isReconcilable(intent)) {
      return intent;
    }
    String mapped = status == null ? "PENDING" : status.getStatus();
    String fromStatus = intent.getStatus().name();

    if ("SUCCESS".equals(mapped)) {
      String mismatch = amountOrCurrencyMismatch(intent, status.getAmount(), status.getCurrency());
      if (mismatch != null) {
        return markCaptureAnomaly(
            intent, status.getExternalPaymentId(), status.getProviderStatusCode(), mismatch, null);
      }
      if (Boolean.TRUE.equals(intent.getSaveCardRequested())
          && status.getCardToken() != null
          && !status.getCardToken().isBlank()) {
        savedCardService.upsertSavedCard(
            intent.getUser(),
            FreedomPayGateway.PROVIDER_NAME,
            status.getCardToken(),
            status.getCardPanMask());
      }
      return finalizeSuccessfulPayment(
          intent.getId(),
          status.getExternalPaymentId(),
          status.getProviderStatusCode(),
          status.getCardPanMask(),
          null,
          null,
          actorUserId,
          eventPrefix + "_SUCCESS");
    }
    if ("FAILED".equals(mapped)) {
      intent.setStatus(PaymentIntentStatus.FAILED);
      intent.setProviderStatusCode(status.getProviderStatusCode());
      intent.setFailureCode("GATEWAY_FAILED");
      intent.setFailureMessage("Gateway reported the payment as failed");
      releaseReservation(intent, eventPrefix + "_FAILED");
      intent = paymentIntentRepository.save(intent);
      eventLogger.log(
          "INTENT",
          intent.getId(),
          eventPrefix + "_FAILED",
          fromStatus,
          intent.getStatus().name(),
          actorUserId,
          null,
          intent.getIdempotencyKey(),
          Map.of("providerStatus", String.valueOf(status.getProviderStatusCode())));
      return intent;
    }
    if ("REVIEW".equals(mapped)
        || (status != null && status.getProviderState() == ProviderPaymentState.AUTHORIZED)) {
      // Captured-then-refunded, or authorized-but-not-captured (two-step): neither is money EcoPay
      // may treat as a completed payment, and neither is a plain failure.
      if (OPEN_INTENT_STATUSES.contains(intent.getStatus())
          && intent.getStatus() != PaymentIntentStatus.RECONCILING) {
        intent.setStatus(PaymentIntentStatus.RECONCILING);
      }
      intent.setReviewRequired(true);
      intent.setReviewReason("PROVIDER_STATE_" + status.getProviderState());
      intent.setProviderStatusCode(status.getProviderStatusCode());
      intent = paymentIntentRepository.save(intent);
      eventLogger.log(
          "INTENT",
          intent.getId(),
          eventPrefix + "_REVIEW",
          fromStatus,
          intent.getStatus().name(),
          actorUserId,
          null,
          intent.getIdempotencyKey(),
          Map.of("providerState", String.valueOf(status.getProviderState())));
      return intent;
    }
    if (status != null
        && status.getExternalPaymentId() != null
        && (intent.getExternalPaymentId() == null || intent.getExternalPaymentId().isBlank())) {
      intent.setExternalPaymentId(status.getExternalPaymentId());
      intent = paymentIntentRepository.save(intent);
    }
    // else: still PENDING at the gateway — leave as-is; webhook/poll will finalize.
    return intent;
  }

  private static String amountOrCurrencyMismatch(
      PaymentIntent intent, BigDecimal amount, String currency) {
    if (amount != null && intent.getAmount() != null && intent.getAmount().compareTo(amount) != 0) {
      return "AMOUNT_MISMATCH";
    }
    if (currency != null && !currency.isBlank() && !"KZT".equalsIgnoreCase(currency.trim())) {
      return "CURRENCY_MISMATCH";
    }
    return null;
  }

  private PaymentIntent markCaptureAnomaly(
      PaymentIntent intent,
      String externalPaymentId,
      String providerStatusCode,
      String reason,
      String providerRequestId) {
    String fromStatus = intent.getStatus().name();
    log.error("Captured payment for intent {} is inconsistent: {}", intent.getId(), reason);
    intent.setStatus(PaymentIntentStatus.CAPTURE_ANOMALY);
    if (externalPaymentId != null && !externalPaymentId.isBlank()) {
      intent.setExternalPaymentId(externalPaymentId);
    }
    intent.setProviderStatusCode(providerStatusCode);
    intent.setFailureCode(reason);
    intent.setFailureMessage("Provider-reported payment does not match the intent: " + reason);
    intent.setReviewRequired(true);
    intent.setReviewReason(reason);
    intent.setCompensationRequired(true);
    intent = paymentIntentRepository.save(intent);
    eventLogger.log(
        "INTENT",
        intent.getId(),
        "CAPTURE_ANOMALY",
        fromStatus,
        intent.getStatus().name(),
        null,
        providerRequestId,
        intent.getIdempotencyKey(),
        Map.of("reason", reason));
    return intent;
  }

  private record ReconcileTarget(
      Long intentId,
      String providerName,
      String externalPaymentId,
      boolean reconcilable,
      PaymentIntentResponse response) {}

  /**
   * Process a verified webhook event. Caller must have already saved the inbox row (idempotency)
   * and verified the signature.
   */
  @Transactional
  public void applyWebhookEvent(GatewayWebhookEvent event) {
    // Async payout result callback (no intent id) — route to the payout service.
    if ("PAYOUT".equals(event.getKind())) {
      if ("PENDING".equals(event.getResultStatus())) {
        return;
      }
      payoutService.applyPayoutWebhook(
          event.getExternalPaymentId(),
          event.getOrderId(),
          "SUCCESS".equals(event.getResultStatus()),
          event.getAmount());
      return;
    }

    // Async refund result callback — route to the refund service.
    if ("REFUND".equals(event.getKind())) {
      if ("PENDING".equals(event.getResultStatus())) {
        return;
      }
      refundService.applyRefundWebhook(
          event.getExternalPaymentId(), "SUCCESS".equals(event.getResultStatus()));
      return;
    }

    if (event.getIntentId() == null) {
      throw new FreedomWebhookProcessingException(
          "MISSING_INTENT_ID", "Webhook event has no payment intent id", false);
    }

    PaymentIntent intent =
        paymentIntentRepository.findWithLockById(event.getIntentId()).orElse(null);
    if (intent == null) {
      throw new FreedomWebhookProcessingException(
          "INTENT_NOT_FOUND",
          "Webhook references unknown payment intent " + event.getIntentId(),
          true);
    }

    intent.setLastWebhookAt(LocalDateTime.now());

    if (PROVIDER_CAPTURED_STATUSES.contains(intent.getStatus())) {
      // Provider-captured states are terminal from the charging perspective; audit duplicates.
      eventLogger.log(
          "INTENT",
          intent.getId(),
          "WEBHOOK_LATE_DUPLICATE",
          intent.getStatus().name(),
          intent.getStatus().name(),
          null,
          event.getProviderRequestId(),
          intent.getIdempotencyKey(),
          Map.of("resultStatus", String.valueOf(event.getResultStatus())));
      paymentIntentRepository.save(intent);
      return;
    }

    if ("SUCCESS".equals(event.getResultStatus()) && Boolean.FALSE.equals(event.getCaptured())) {
      // Two-step authorization (pg_captured=0): money is only held on the card. Not a completed
      // payment — keep the intent open for status reconciliation instead of activating anything.
      String fromStatus = intent.getStatus().name();
      if (OPEN_INTENT_STATUSES.contains(intent.getStatus())) {
        intent.setStatus(PaymentIntentStatus.RECONCILING);
      }
      if (event.getExternalPaymentId() != null) {
        intent.setExternalPaymentId(event.getExternalPaymentId());
      }
      intent.setReviewReason("PROVIDER_STATE_AUTHORIZED");
      paymentIntentRepository.save(intent);
      eventLogger.log(
          "INTENT",
          intent.getId(),
          "WEBHOOK_AUTHORIZED_NOT_CAPTURED",
          fromStatus,
          intent.getStatus().name(),
          null,
          event.getProviderRequestId(),
          intent.getIdempotencyKey(),
          Map.of());
      return;
    }

    if ("SUCCESS".equals(event.getResultStatus())) {
      // Defence-in-depth: never trust a SUCCESS callback whose amount/currency
      // does not match the intent we created. The signature already covers the
      // amount, but a mismatch means tampering or a provider bug → treat as FAILED.
      if (event.getAmount() != null && intent.getAmount().compareTo(event.getAmount()) != 0) {
        log.error(
            "Webhook amount mismatch for intent {}: expected {} got {}; marking capture anomaly",
            intent.getId(),
            intent.getAmount(),
            event.getAmount());
        intent.setStatus(PaymentIntentStatus.CAPTURE_ANOMALY);
        intent.setExternalPaymentId(event.getExternalPaymentId());
        intent.setProviderStatusCode(event.getProviderStatusCode());
        intent.setFailureCode("AMOUNT_MISMATCH");
        intent.setFailureMessage(
            "Callback amount "
                + event.getAmount()
                + " does not match intent amount "
                + intent.getAmount());
        intent.setReviewRequired(true);
        intent.setReviewReason("AMOUNT_MISMATCH");
        intent.setCompensationRequired(true);
        paymentIntentRepository.save(intent);
        eventLogger.log(
            "INTENT",
            intent.getId(),
            "WEBHOOK_AMOUNT_MISMATCH",
            "PENDING",
            PaymentIntentStatus.CAPTURE_ANOMALY.name(),
            null,
            event.getProviderRequestId(),
            intent.getIdempotencyKey(),
            Map.of(
                "expected",
                intent.getAmount().toPlainString(),
                "received",
                event.getAmount().toPlainString()));
        return;
      }
      if (event.getCurrency() != null
          && !event.getCurrency().isBlank()
          && !"KZT".equalsIgnoreCase(event.getCurrency())) {
        log.error(
            "Webhook currency mismatch for intent {}: got {}; marking capture anomaly",
            intent.getId(),
            event.getCurrency());
        intent.setStatus(PaymentIntentStatus.CAPTURE_ANOMALY);
        intent.setExternalPaymentId(event.getExternalPaymentId());
        intent.setProviderStatusCode(event.getProviderStatusCode());
        intent.setFailureCode("CURRENCY_MISMATCH");
        intent.setFailureMessage("Callback currency " + event.getCurrency() + " is not KZT");
        intent.setReviewRequired(true);
        intent.setReviewReason("CURRENCY_MISMATCH");
        intent.setCompensationRequired(true);
        paymentIntentRepository.save(intent);
        return;
      }
      if (Boolean.TRUE.equals(intent.getSaveCardRequested())
          && event.getCardToken() != null
          && !event.getCardToken().isBlank()) {
        savedCardService.upsertSavedCard(
            intent.getUser(),
            FreedomPayGateway.PROVIDER_NAME,
            event.getCardToken(),
            event.getCardPanMask());
      }

      finalizeSuccessfulPayment(
          intent.getId(),
          event.getExternalPaymentId(),
          event.getProviderStatusCode(),
          event.getCardPanMask(),
          event.getSignature(),
          event.getProviderRequestId(),
          null,
          "WEBHOOK_SUCCESS");
    } else if ("FAILED".equals(event.getResultStatus())) {
      String fromStatus = intent.getStatus().name();
      intent.setStatus(PaymentIntentStatus.FAILED);
      intent.setExternalPaymentId(event.getExternalPaymentId());
      intent.setProviderStatusCode(event.getProviderStatusCode());
      intent.setFailureCode(event.getFailureCode());
      intent.setFailureMessage(event.getFailureMessage());
      releaseReservation(intent, "WEBHOOK_FAILED");
      paymentIntentRepository.save(intent);

      eventLogger.log(
          "INTENT",
          intent.getId(),
          "WEBHOOK_FAILED",
          fromStatus,
          intent.getStatus().name(),
          null,
          event.getProviderRequestId(),
          intent.getIdempotencyKey(),
          Map.of(
              "failureCode",
              String.valueOf(event.getFailureCode()),
              "failureMessage",
              String.valueOf(event.getFailureMessage())));
    } else {
      log.info("Webhook with non-terminal status {}, ignoring", event.getResultStatus());
    }
  }

  @Transactional
  public PaymentIntent finalizeSuccessfulPayment(
      Long paymentIntentId,
      String externalPaymentId,
      String providerStatusCode,
      String cardPanMask,
      String providerSignature,
      String providerRequestId,
      Long actorUserId,
      String eventType) {
    PaymentIntent intent =
        paymentIntentRepository
            .findWithLockById(paymentIntentId)
            .orElseThrow(() -> new ResourceNotFoundException("Payment intent not found"));

    if (PROVIDER_CAPTURED_STATUSES.contains(intent.getStatus())) {
      eventLogger.log(
          "INTENT",
          intent.getId(),
          eventType + "_DUPLICATE",
          intent.getStatus().name(),
          intent.getStatus().name(),
          actorUserId,
          providerRequestId,
          intent.getIdempotencyKey(),
          Map.of());
      return intent;
    }
    if (!OPEN_INTENT_STATUSES.contains(intent.getStatus())
        && intent.getStatus() != PaymentIntentStatus.EXPIRED
        && intent.getStatus() != PaymentIntentStatus.FAILED) {
      return intent;
    }

    String fromStatus = intent.getStatus().name();
    if (intent.getCapturedAt() == null) {
      intent.setCapturedAt(LocalDateTime.now(clock));
    }
    if (externalPaymentId != null && !externalPaymentId.isBlank()) {
      intent.setExternalPaymentId(externalPaymentId);
    }
    if (providerStatusCode != null && !providerStatusCode.isBlank()) {
      intent.setProviderStatusCode(providerStatusCode);
    }
    SeatConsumptionResult seat = consumeReservedSeatIfAvailable(intent);
    if (!seat.accepted()) {
      PaymentTransaction chargeTx =
          recordSuccessTransaction(intent, cardPanMask, providerSignature, false);
      intent.setStatus(PaymentIntentStatus.REFUND_REQUIRED);
      intent.setCompensationRequired(true);
      intent.setReviewRequired(true);
      intent.setReviewReason(seat.reason());
      intent = paymentIntentRepository.save(intent);
      eventLogger.log(
          "INTENT",
          intent.getId(),
          "COMPENSATION_REQUIRED",
          fromStatus,
          intent.getStatus().name(),
          actorUserId,
          providerRequestId,
          intent.getIdempotencyKey(),
          Map.of(
              "roomId",
              String.valueOf(intent.getRoomMember().getRoom().getId()),
              "reason",
              seat.reason()));
      log.error(
          "Payment intent {} succeeded at provider but room {} cannot consume reservation ({}); automatic refund required",
          intent.getId(),
          intent.getRoomMember().getRoom().getId(),
          seat.reason());
      try {
        RefundTransaction refund =
            refundService.createAutomaticCompensationRefund(chargeTx, seat.reason());
        if (refund.getStatus() == RefundStatus.SUCCESS) {
          intent.setStatus(PaymentIntentStatus.REFUNDED);
          intent.setReviewRequired(false);
        } else if (refund.getStatus() == RefundStatus.PENDING) {
          intent.setStatus(PaymentIntentStatus.REFUND_PENDING);
        } else {
          intent.setStatus(PaymentIntentStatus.REQUIRES_REVIEW);
          intent.setReviewRequired(true);
        }
      } catch (Exception ex) {
        log.error(
            "Automatic compensation refund failed for intent {}: {}",
            intent.getId(),
            ex.getMessage());
        intent.setStatus(PaymentIntentStatus.REQUIRES_REVIEW);
        intent.setReviewRequired(true);
        intent.setReviewReason(seat.reason() + ": " + ex.getMessage());
      }
      intent = paymentIntentRepository.save(intent);
      return intent;
    }

    intent.setStatus(PaymentIntentStatus.SUCCESS);
    intent.setCompensationRequired(false);
    intent.setReviewRequired(false);
    intent.setReviewReason(null);
    intent = paymentIntentRepository.save(intent);

    recordSuccessTransaction(intent, cardPanMask, providerSignature, true);
    roomMemberService.markMembershipAsPaid(intent.getRoomMember());
    payoutService.createOwnerPayoutForSuccessfulPayment(intent);

    RoomMember member = intent.getRoomMember();
    roomEventLogger.log(
        member == null ? null : member.getRoom(),
        member,
        intent.getUser(),
        "MEMBER",
        "payment_success",
        Map.of(
            "intentId",
            String.valueOf(intent.getId()),
            "amount",
            String.valueOf(intent.getAmount())));

    Room room = member == null ? null : member.getRoom();
    notificationService.notify(
        intent.getUser(),
        NotificationType.PAYMENT_SUCCESS,
        "Оплата подтверждена",
        "Оплата"
            + (room == null ? "" : " за участие в комнате «" + room.getTitle() + "»")
            + " на сумму "
            + intent.getAmount()
            + (room == null || room.getCurrency() == null ? " KZT" : " " + room.getCurrency())
            + " прошла успешно.",
        room == null ? null : "/rooms/member/" + room.getId(),
        Map.of("intentId", intent.getId(), "roomId", room == null ? 0L : room.getId()));

    eventLogger.log(
        "INTENT",
        intent.getId(),
        eventType,
        fromStatus,
        intent.getStatus().name(),
        actorUserId,
        providerRequestId,
        intent.getIdempotencyKey(),
        Map.of("externalPaymentId", String.valueOf(intent.getExternalPaymentId())));
    return intent;
  }

  private SeatConsumptionResult consumeReservedSeatIfAvailable(PaymentIntent intent) {
    RoomMember member =
        roomMemberRepository
            .findWithLockById(intent.getRoomMember().getId())
            .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));
    Room room =
        roomRepository
            .findByIdForUpdate(member.getRoom().getId())
            .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

    if (!isRoomPayable(room)) {
      return SeatConsumptionResult.rejected("ROOM_NOT_PAYABLE_" + room.getStatus());
    }
    if (!isActiveUser(member.getUser())) {
      return SeatConsumptionResult.rejected("MEMBER_USER_NOT_ACTIVE");
    }
    if (member.getDeletedAt() != null
        || (member.getStatus() != MemberStatus.APPLIED
            && member.getStatus() != MemberStatus.PENDING
            && member.getStatus() != MemberStatus.ACTIVE)) {
      // REJECTED / CANCELLED_BEFORE_PAYMENT / BLOCKED_BY_ADMIN cannot be marked paid; without this
      // the whole finalize would roll back and the captured money would sit in the DLQ.
      return SeatConsumptionResult.rejected("MEMBERSHIP_NOT_PAYABLE_" + member.getStatus());
    }

    PaymentReservation reservation =
        paymentReservationRepository.findWithLockByPaymentIntentId(intent.getId()).orElse(null);
    LocalDateTime now = LocalDateTime.now();
    if (reservation != null) {
      if (reservation.getStatus() == PaymentReservationStatus.RESERVED) {
        boolean reservationStillActive = reservation.getExpiresAt().isAfter(now);
        if (!reservationStillActive && !hasCapacityForLateSuccess(room, member)) {
          return SeatConsumptionResult.rejected("NO_CAPACITY_AFTER_RESERVATION_EXPIRY");
        }
        reservation.setStatus(PaymentReservationStatus.CONSUMED);
        reservation.setConsumedAt(now);
        paymentReservationRepository.save(reservation);
        member.setRoom(room);
        intent.setRoomMember(member);
        return SeatConsumptionResult.ok();
      }
      return SeatConsumptionResult.rejected("RESERVATION_NOT_ACTIVE");
    }

    if (hasCapacityForLateSuccess(room, member)) {
      member.setRoom(room);
      intent.setRoomMember(member);
      return SeatConsumptionResult.ok();
    }

    return SeatConsumptionResult.rejected("NO_CAPACITY");
  }

  private boolean hasCapacityForLateSuccess(Room room, RoomMember member) {
    long occupiedSlots =
        roomMemberRepository.countByRoomAndStatusInAndDeletedAtIsNull(
            room, List.of(MemberStatus.PENDING, MemberStatus.ACTIVE));
    return member.getStatus() == MemberStatus.PENDING
        || member.getStatus() == MemberStatus.ACTIVE
        || !RoomSeatMath.marketplaceFull(room, occupiedSlots);
  }

  private record SeatConsumptionResult(boolean accepted, String reason) {
    static SeatConsumptionResult ok() {
      return new SeatConsumptionResult(true, null);
    }

    static SeatConsumptionResult rejected(String reason) {
      return new SeatConsumptionResult(false, reason);
    }
  }

  private PaymentIntent markIntentUnknownAfterGatewayException(
      Long intentId, Long actorUserId, String error) {
    PaymentIntent intent =
        paymentIntentRepository
            .findWithLockById(intentId)
            .orElseThrow(() -> new ResourceNotFoundException("Payment intent not found"));
    String fromStatus = intent.getStatus().name();
    intent.setStatus(PaymentIntentStatus.UNKNOWN);
    intent.setFailureCode("GATEWAY_INIT_UNKNOWN");
    intent.setFailureMessage("Gateway initiation result is unknown: " + error);
    intent = paymentIntentRepository.save(intent);
    eventLogger.log(
        "INTENT",
        intent.getId(),
        "GATEWAY_INIT_UNKNOWN",
        fromStatus,
        intent.getStatus().name(),
        actorUserId,
        null,
        intent.getIdempotencyKey(),
        Map.of("error", String.valueOf(error)));
    return intent;
  }

  private PaymentIntent failIntentAndReleaseReservation(
      Long intentId, String failureCode, String failureMessage, Long actorUserId, String error) {
    PaymentIntent intent =
        paymentIntentRepository
            .findWithLockById(intentId)
            .orElseThrow(() -> new ResourceNotFoundException("Payment intent not found"));
    intent.setStatus(PaymentIntentStatus.FAILED);
    intent.setFailureCode(failureCode);
    intent.setFailureMessage(failureMessage);
    releaseReservation(intent, failureCode);
    intent = paymentIntentRepository.save(intent);
    eventLogger.log(
        "INTENT",
        intent.getId(),
        failureCode,
        "PENDING",
        "FAILED",
        actorUserId,
        null,
        intent.getIdempotencyKey(),
        Map.of("error", String.valueOf(error)));
    return intent;
  }

  private PaymentIntent findOpenIntentForMember(Long roomMemberId, User currentUser) {
    PaymentIntent intent =
        paymentIntentRepository
            .findFirstByRoomMember_IdAndStatusInOrderByCreatedAtDesc(
                roomMemberId, BLOCKING_INTENT_STATUSES)
            .orElse(null);
    if (intent == null) {
      return null;
    }
    if (intent.getUser() == null || !intent.getUser().getId().equals(currentUser.getId())) {
      throw new ForbiddenOperationException("Not your payment intent");
    }
    return intent;
  }

  private void releaseReservation(PaymentIntent intent, String reason) {
    PaymentReservation reservation =
        paymentReservationRepository.findWithLockByPaymentIntentId(intent.getId()).orElse(null);
    if (reservation == null || reservation.getStatus() != PaymentReservationStatus.RESERVED) {
      return;
    }
    reservation.setStatus(PaymentReservationStatus.RELEASED);
    reservation.setReleasedAt(LocalDateTime.now());
    reservation.setReleaseReason(reason);
    paymentReservationRepository.save(reservation);
  }

  private PaymentIntent requireSameIdempotentRequest(
      PaymentIntent existing,
      Long roomMemberId,
      User currentUser,
      CreatePaymentIntentRequest request) {
    boolean sameUser =
        existing.getUser() != null && existing.getUser().getId().equals(currentUser.getId());
    boolean sameMember =
        existing.getRoomMember() != null && existing.getRoomMember().getId().equals(roomMemberId);
    Long existingSavedCardId =
        existing.getSavedCard() == null ? null : existing.getSavedCard().getId();
    boolean sameSavedCard = java.util.Objects.equals(existingSavedCardId, request.getSavedCardId());
    boolean sameSaveCard =
        java.util.Objects.equals(
            Boolean.TRUE.equals(existing.getSaveCardRequested()),
            Boolean.TRUE.equals(request.getSaveCard()));
    if (!sameUser || !sameMember || !sameSavedCard || !sameSaveCard) {
      throw new ResourceConflictException(
          "IDEMPOTENCY_KEY_CONFLICT", "Idempotency key belongs to a different payment request");
    }
    return existing;
  }

  private TransactionTemplate tx() {
    return new TransactionTemplate(transactionManager);
  }

  private record PreparedPayment(
      PaymentIntent intent,
      String savedCardToken,
      GatewayChargeRequest chargeRequest,
      PaymentIntentResponse response) {
    static PreparedPayment existing(PaymentIntent intent, PaymentIntentResponse response) {
      return new PreparedPayment(intent, null, null, response);
    }
  }

  private PaymentTransaction recordSuccessTransaction(
      PaymentIntent intent,
      String cardPanMask,
      String providerSignature,
      boolean updateMemberPaymentPointers) {
    PaymentTransaction existing =
        paymentTransactionRepository
            .findFirstByPaymentIntentAndTypeAndStatus(
                intent, PaymentTransactionType.CHARGE, PaymentTransactionStatus.SUCCESS)
            .orElse(null);
    if (existing != null) {
      return existing;
    }

    ObjectNode rawPayload = JsonNodeFactory.instance.objectNode();
    rawPayload.put("provider", intent.getProviderName());
    rawPayload.put("paymentIntentId", intent.getId());
    rawPayload.put("roomMemberId", intent.getRoomMember().getId());
    rawPayload.put("externalPaymentId", String.valueOf(intent.getExternalPaymentId()));

    PaymentTransaction tx =
        PaymentTransaction.builder()
            .paymentIntent(intent)
            .room(intent.getRoomMember().getRoom())
            .roomMember(intent.getRoomMember())
            .type(PaymentTransactionType.CHARGE)
            .externalTransactionId(intent.getExternalPaymentId())
            .amount(intent.getAmount())
            .currency("KZT")
            .status(PaymentTransactionStatus.SUCCESS)
            .providerName(intent.getProviderName())
            .rawPayload(rawPayload)
            .providerSignature(providerSignature)
            .cardPanMask(cardPanMask)
            .capturedAt(intent.getCapturedAt())
            .build();
    tx = paymentTransactionRepository.save(tx);
    RoomMember member = intent.getRoomMember();
    if (updateMemberPaymentPointers) {
      member.setPaymentIntentId(intent.getId());
      member.setLatestPaymentTxId(tx.getId());
      roomMemberRepository.save(member);
    }

    moneyLedgerService.append(
        "PAYMENT_CAPTURE",
        intent.getAmount(),
        "KZT",
        "CREDIT",
        intent,
        tx,
        null,
        null,
        member.getRoom().getOwner(),
        "capture-intent-" + intent.getId());
    if (intent.getCommissionAmount() != null && intent.getCommissionAmount().signum() > 0) {
      moneyLedgerService.append(
          "PLATFORM_FEE",
          intent.getCommissionAmount(),
          "KZT",
          "CREDIT",
          intent,
          tx,
          null,
          null,
          member.getRoom().getOwner(),
          "platform-fee-intent-" + intent.getId());
    }
    return tx;
  }

  private PaymentIntentResponse mapToResponse(PaymentIntent intent) {
    BigDecimal commission =
        intent.getCommissionAmount() == null ? BigDecimal.ZERO : intent.getCommissionAmount();
    BigDecimal share = intent.getAmount() == null ? null : intent.getAmount().subtract(commission);
    Room room = intent.getRoomMember() == null ? null : intent.getRoomMember().getRoom();
    return PaymentIntentResponse.builder()
        .id(intent.getId())
        .idempotencyKey(intent.getIdempotencyKey())
        .amount(intent.getAmount())
        .shareAmount(share)
        .shareKzt(share)
        .commissionAmount(commission)
        .commissionKzt(commission)
        .payableTotalKzt(intent.getAmount())
        .currency("KZT")
        .settlementCurrency("KZT")
        .originalPrice(room == null ? null : room.getPriceTotal())
        .originalCurrency(room == null ? null : room.getCurrency())
        .status(intent.getStatus())
        .providerName(intent.getProviderName())
        .externalPaymentId(intent.getExternalPaymentId())
        .roomMemberId(intent.getRoomMember().getId())
        .paymentUrl(intent.getPaymentUrl())
        .requiresRedirect(
            intent.getPaymentUrl() != null && intent.getStatus() == PaymentIntentStatus.PENDING)
        .saveCardRequested(intent.getSaveCardRequested())
        .expiresAt(intent.getExpiresAt())
        .compensationRequired(intent.getCompensationRequired())
        .reviewRequired(intent.getReviewRequired())
        .reviewReason(intent.getReviewReason())
        .failureCode(intent.getFailureCode())
        .failureMessage(intent.getFailureMessage())
        .build();
  }

  /** The per-member tariff share (before the EcoPay commission is added on top). */
  private BigDecimal resolveShareAmount(Room room) {
    if (room == null) {
      throw new InvalidRequestException(
          "Room configuration is required to calculate payment amount");
    }

    if (isPositiveAmount(room.getPricePerMemberKzt())) {
      return normalizeMoneyAmount(room.getPricePerMemberKzt(), "Room pricePerMemberKzt");
    }

    if (!isPositiveAmount(room.getPriceTotalKzt())) {
      throw new InvalidRequestException(
          "Cannot determine payment amount: room must have frozen positive KZT price snapshot");
    }

    int participantCount = resolveParticipantCount(room);

    try {
      BigDecimal share =
          room.getPriceTotalKzt()
              .divide(BigDecimal.valueOf(participantCount), MONEY_SCALE, RoundingMode.UNNECESSARY);
      return normalizeMoneyAmount(share, "Calculated KZT payment amount");
    } catch (ArithmeticException ex) {
      throw new InvalidRequestException(
          "Cannot determine payment amount: frozen KZT total cannot be split across member slots without rounding");
    }
  }

  private void ensurePaymentAllowedUnderRoomLock(
      Room lockedRoom, RoomMember roomMember, User currentUser) {
    if (!isRoomPayable(lockedRoom)) {
      throw new InvalidRequestException("Room is not payable in status " + lockedRoom.getStatus());
    }
    if (lockedRoom.getStartDate() != null
        && !LocalDateTime.now().isBefore(lockedRoom.getStartDate())) {
      throw new InvalidRequestException("Payment window is closed for this room");
    }
    if (!isActiveUser(currentUser) || !isActiveUser(roomMember.getUser())) {
      throw new InvalidRequestException("Inactive users cannot create payments");
    }
    if (roomMember.getDeletedAt() != null || roomMember.getStatus() != MemberStatus.APPLIED) {
      throw new InvalidRequestException(
          "Payment intent can only be created for active APPLIED membership");
    }
  }

  private boolean isRoomPayable(Room room) {
    return room != null
        && room.getDeletedAt() == null
        && (room.getStatus() == RoomStatus.OPEN
            || room.getStatus() == RoomStatus.IN_VERIFICATION
            || room.getStatus() == RoomStatus.ACTIVE);
  }

  private boolean isActiveUser(User user) {
    return user != null
        && user.getDeletedAt() == null
        && (user.getStatus() == null || user.getStatus() == UserStatus.ACTIVE);
  }

  private int resolveParticipantCount(Room room) {
    Integer maxMembers = room.getMaxMembers();
    if (maxMembers == null || maxMembers < 2) {
      throw new InvalidRequestException(
          "Cannot determine payment amount: room maxMembers must be at least 2");
    }

    return maxMembers;
  }

  private BigDecimal normalizeMoneyAmount(BigDecimal amount, String fieldName) {
    if (amount == null) {
      throw new InvalidRequestException(fieldName + " must not be null");
    }

    if (amount.signum() <= 0) {
      throw new InvalidRequestException(fieldName + " must be greater than 0");
    }

    try {
      return amount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException ex) {
      throw new InvalidRequestException(fieldName + " must have at most 2 decimal places");
    }
  }

  private boolean isPositiveAmount(BigDecimal amount) {
    return amount != null && amount.signum() > 0;
  }
}
