package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway;
import kz.hrms.splitupauth.repository.PaymentIntentRepository;
import kz.hrms.splitupauth.repository.PaymentReservationRepository;
import kz.hrms.splitupauth.repository.PaymentTransactionRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.SavedCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

  private static final int MONEY_SCALE = 2;
  private static final long PAYMENT_INTENT_TTL_MINUTES = 30;
  private static final List<PaymentIntentStatus> OPEN_INTENT_STATUSES =
      List.of(
          PaymentIntentStatus.PENDING,
          PaymentIntentStatus.UNKNOWN,
          PaymentIntentStatus.RECONCILING);
  private static final List<PaymentIntentStatus> PROVIDER_CAPTURED_STATUSES =
      List.of(
          PaymentIntentStatus.SUCCESS,
          PaymentIntentStatus.REFUND_REQUIRED,
          PaymentIntentStatus.REFUND_PENDING,
          PaymentIntentStatus.REFUNDED,
          PaymentIntentStatus.REQUIRES_REVIEW);

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
  private final PlatformTransactionManager transactionManager;

  public PaymentIntentResponse createPaymentIntent(
      Long roomMemberId, User currentUser, CreatePaymentIntentRequest request) {
    PreparedPayment prepared;
    try {
      prepared =
          tx()
              .execute(
                  status -> createIntentAndReservation(roomMemberId, currentUser, request));
    } catch (DataIntegrityViolationException ex) {
      PaymentIntent existing =
          tx()
              .execute(
                  status ->
                      paymentIntentRepository
                          .findByIdempotencyKey(request.getIdempotencyKey())
                          .orElse(null));
      if (existing != null) {
        PaymentIntent sameRequest =
            tx()
                .execute(
                    status ->
                        requireSameIdempotentRequest(
                            existing, roomMemberId, currentUser, request));
        return mapToResponse(sameRequest);
      }
      PaymentIntent openIntent =
          tx().execute(status -> findOpenIntentForMember(roomMemberId, currentUser));
      if (openIntent != null) {
        return mapToResponse(openIntent);
      }
      throw ex;
    }

    PaymentIntent intent = prepared.intent();
    if (intent.getStatus() != PaymentIntentStatus.PENDING || prepared.chargeRequest() == null) {
      return mapToResponse(intent);
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
          "Gateway charge initiation failed for intent {}: {}", intent.getId(), ex.getMessage());
      PaymentIntent failed =
          tx()
              .execute(
                  status ->
                      markIntentUnknownAfterGatewayException(
                          intent.getId(),
                          currentUser.getId(),
                          ex.getMessage()));
      return mapToResponse(failed);
    }

    PaymentIntent updated =
        tx().execute(status -> applyGatewayInitResponse(intent.getId(), chargeResp, currentUser.getId()));

    if (chargeResp.isSuccess() && !chargeResp.isRequiresRedirect()) {
      Long updatedIntentId = updated.getId();
      updated =
          tx()
              .execute(
                  status ->
                      finalizeSuccessfulPayment(
                          updatedIntentId,
                          chargeResp.getExternalPaymentId(),
                          chargeResp.getProviderStatusCode(),
                          null,
                          null,
                          null,
                          currentUser.getId(),
                          "GATEWAY_SYNC_SUCCESS"));
    }

    return mapToResponse(updated);
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
      return PreparedPayment.existing(existing);
    }

    existing = findOpenIntentForMember(roomMemberId, currentUser);
    if (existing != null) {
      return PreparedPayment.existing(existing);
    }

    if (roomMember.getStatus() != MemberStatus.APPLIED) {
      throw new InvalidRequestException(
          "Payment intent can only be created for APPLIED membership");
    }

    PaymentGateway gateway = gatewayRegistry.defaultGateway();
    // The member pays their tariff share plus the EcoPay commission (owner pays none).
    // The owner is later paid the share; EcoPay keeps the commission (see PayoutService).
    BigDecimal share = resolveShareAmount(roomMember.getRoom());
    BigDecimal commission = commissionCalculator.commissionFor(share);
    BigDecimal amount = share.add(commission);

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

    existing = findOpenIntentForMember(roomMemberId, currentUser);
    if (existing != null) {
      return PreparedPayment.existing(existing);
    }

    long paidSeats =
        roomMemberRepository.countByRoomAndStatusInAndDeletedAtIsNull(
            lockedRoom, List.of(MemberStatus.PENDING, MemberStatus.ACTIVE));
    LocalDateTime now = LocalDateTime.now();
    long activeReservations =
        paymentReservationRepository.countByRoomAndStatusAndExpiresAtAfter(
            lockedRoom, PaymentReservationStatus.RESERVED, now);
    if (paidSeats + activeReservations >= lockedRoom.getMaxMembers() - 1L) {
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
    return new PreparedPayment(intent, savedCardToken, chargeReq);
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
        "Платёж принят",
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
    List<PaymentIntent> stale =
        OPEN_INTENT_STATUSES.stream()
            .flatMap(
                status ->
                    paymentIntentRepository
                        .findByStatusAndExpiresAtBefore(status, LocalDateTime.now())
                        .stream())
            .toList();
    for (PaymentIntent intent : stale) {
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
  public PaymentIntentResponse getCurrentPaymentIntentForMember(Long roomMemberId, User currentUser) {
    PaymentIntent intent = findOpenIntentForMember(roomMemberId, currentUser);
    if (intent == null) {
      throw new ResourceNotFoundException("Open payment intent not found");
    }
    return mapToResponse(intent);
  }

  @Transactional
  public PaymentIntentResponse confirmPaymentSuccess(
      Long paymentIntentId, User currentUser, ConfirmPaymentRequest request) {
    // Redirect-back reconciliation. The async webhook (result.php) is the
    // primary source of truth, but it needs a publicly reachable URL. When
    // the user is redirected back from the hosted payment page we actively
    // query the gateway for the payment status and finalize if it already
    // succeeded — this makes the flow complete end-to-end even when the
    // inbound webhook cannot reach us (e.g. local dev without a tunnel).
    PaymentIntent intent =
        paymentIntentRepository
            .findWithLockById(paymentIntentId)
            .orElseThrow(() -> new ResourceNotFoundException("Payment intent not found"));

    if (!intent.getUser().getId().equals(currentUser.getId())) {
      throw new ForbiddenOperationException("Not your payment intent");
    }

    // Already captured at provider (e.g. the webhook arrived first) - nothing to do.
    if (PROVIDER_CAPTURED_STATUSES.contains(intent.getStatus())) {
      return mapToResponse(intent);
    }
    if (!OPEN_INTENT_STATUSES.contains(intent.getStatus())
        && intent.getStatus() != PaymentIntentStatus.EXPIRED
        && intent.getStatus() != PaymentIntentStatus.FAILED) {
      return mapToResponse(intent);
    }

    // No external id means the charge was never initiated at the gateway.
    if (intent.getExternalPaymentId() == null || intent.getExternalPaymentId().isBlank()) {
      return mapToResponse(intent);
    }

    PaymentGateway gateway = gatewayRegistry.resolve(intent.getProviderName());
    GatewayStatusResponse status;
    try {
      status = gateway.getStatus(intent.getExternalPaymentId());
    } catch (Exception ex) {
      log.warn("Status reconcile failed for intent {}: {}", intent.getId(), ex.getMessage());
      if (OPEN_INTENT_STATUSES.contains(intent.getStatus())) {
        String fromStatus = intent.getStatus().name();
        intent.setStatus(PaymentIntentStatus.RECONCILING);
        intent.setFailureCode("GATEWAY_STATUS_UNKNOWN");
        intent.setFailureMessage("Gateway status check failed: " + ex.getMessage());
        intent = paymentIntentRepository.save(intent);
        eventLogger.log(
            "INTENT",
            intent.getId(),
            "REDIRECT_RECONCILE_UNKNOWN",
            fromStatus,
            intent.getStatus().name(),
            currentUser.getId(),
            null,
            intent.getIdempotencyKey(),
            Map.of("error", String.valueOf(ex.getMessage())));
      }
      return mapToResponse(intent);
    }

    String mapped = status == null ? "PENDING" : status.getStatus();

    if ("SUCCESS".equals(mapped)) {
      if (Boolean.TRUE.equals(intent.getSaveCardRequested())
          && status.getCardToken() != null
          && !status.getCardToken().isBlank()) {
        savedCardService.upsertSavedCard(
            intent.getUser(),
            FreedomPayGateway.PROVIDER_NAME,
            status.getCardToken(),
            status.getCardPanMask());
      }

      intent =
          finalizeSuccessfulPayment(
              intent.getId(),
              status.getExternalPaymentId(),
              status.getProviderStatusCode(),
              status.getCardPanMask(),
              null,
              null,
              currentUser.getId(),
              "REDIRECT_RECONCILE_SUCCESS");
    } else if ("FAILED".equals(mapped)) {
      String fromStatus = intent.getStatus().name();
      intent.setStatus(PaymentIntentStatus.FAILED);
      intent.setProviderStatusCode(status.getProviderStatusCode());
      intent.setFailureCode("GATEWAY_FAILED");
      intent.setFailureMessage("Gateway reported the payment as failed");
      releaseReservation(intent, "REDIRECT_RECONCILE_FAILED");
      intent = paymentIntentRepository.save(intent);

      eventLogger.log(
          "INTENT",
          intent.getId(),
          "REDIRECT_RECONCILE_FAILED",
          fromStatus,
          intent.getStatus().name(),
          currentUser.getId(),
          null,
          intent.getIdempotencyKey(),
          Map.of("providerStatus", String.valueOf(status.getProviderStatusCode())));
    }
    // else: still PENDING at the gateway — leave as-is; webhook/poll will finalize.

    return mapToResponse(intent);
  }

  /**
   * Process a verified webhook event. Caller must have already saved the inbox row (idempotency)
   * and verified the signature.
   */
  @Transactional
  public void applyWebhookEvent(GatewayWebhookEvent event) {
    // Async payout result callback (no intent id) — route to the payout service.
    if ("PAYOUT".equals(event.getKind())) {
      payoutService.applyPayoutWebhook(
          event.getExternalPaymentId(), "SUCCESS".equals(event.getResultStatus()));
      return;
    }

    // Async refund result callback — route to the refund service.
    if ("REFUND".equals(event.getKind())) {
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

    if ("SUCCESS".equals(event.getResultStatus())) {
      // Defence-in-depth: never trust a SUCCESS callback whose amount/currency
      // does not match the intent we created. The signature already covers the
      // amount, but a mismatch means tampering or a provider bug → treat as FAILED.
      if (event.getAmount() != null && intent.getAmount().compareTo(event.getAmount()) != 0) {
        log.error(
            "Webhook amount mismatch for intent {}: expected {} got {} — rejecting",
            intent.getId(),
            intent.getAmount(),
            event.getAmount());
        intent.setStatus(PaymentIntentStatus.FAILED);
        intent.setFailureCode("AMOUNT_MISMATCH");
        intent.setFailureMessage(
            "Callback amount "
                + event.getAmount()
                + " does not match intent amount "
                + intent.getAmount());
        paymentIntentRepository.save(intent);
        releaseReservation(intent, "AMOUNT_MISMATCH");
        eventLogger.log(
            "INTENT",
            intent.getId(),
            "WEBHOOK_AMOUNT_MISMATCH",
            "PENDING",
            "FAILED",
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
            "Webhook currency mismatch for intent {}: got {} — rejecting",
            intent.getId(),
            event.getCurrency());
        intent.setStatus(PaymentIntentStatus.FAILED);
        intent.setFailureCode("CURRENCY_MISMATCH");
        intent.setFailureMessage("Callback currency " + event.getCurrency() + " is not KZT");
        releaseReservation(intent, "CURRENCY_MISMATCH");
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
    if (externalPaymentId != null && !externalPaymentId.isBlank()) {
      intent.setExternalPaymentId(externalPaymentId);
    }
    if (providerStatusCode != null && !providerStatusCode.isBlank()) {
      intent.setProviderStatusCode(providerStatusCode);
    }
    SeatConsumptionResult seat = consumeReservedSeatIfAvailable(intent);
    if (!seat.accepted()) {
      PaymentTransaction chargeTx = recordSuccessTransaction(intent, cardPanMask, providerSignature, false);
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
        log.error("Automatic compensation refund failed for intent {}: {}", intent.getId(), ex.getMessage());
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
        "Payment accepted",
        "Payment"
            + (room == null ? "" : " for room \"" + room.getTitle() + "\"")
            + " in amount "
            + intent.getAmount()
            + (room == null ? "" : " " + room.getCurrency())
            + " succeeded.",
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
        || occupiedSlots < room.getMaxMembers() - 1L;
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
                roomMemberId, OPEN_INTENT_STATUSES)
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
      PaymentIntent existing, Long roomMemberId, User currentUser, CreatePaymentIntentRequest request) {
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
      PaymentIntent intent, String savedCardToken, GatewayChargeRequest chargeRequest) {
    static PreparedPayment existing(PaymentIntent intent) {
      return new PreparedPayment(intent, null, null);
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
    return PaymentIntentResponse.builder()
        .id(intent.getId())
        .idempotencyKey(intent.getIdempotencyKey())
        .amount(intent.getAmount())
        .shareAmount(share)
        .commissionAmount(commission)
        .currency("KZT")
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

    if (isPositiveAmount(room.getPricePerMember())) {
      return normalizeMoneyAmount(room.getPricePerMember(), "Room pricePerMember");
    }

    if (!isPositiveAmount(room.getPriceTotal())) {
      throw new InvalidRequestException(
          "Cannot determine payment amount: room must have positive pricePerMember or positive priceTotal");
    }

    int participantCount = resolveParticipantCount(room);

    try {
      BigDecimal share =
          room.getPriceTotal()
              .divide(BigDecimal.valueOf(participantCount), MONEY_SCALE, RoundingMode.UNNECESSARY);
      return normalizeMoneyAmount(share, "Calculated payment amount");
    } catch (ArithmeticException ex) {
      throw new InvalidRequestException(
          "Cannot determine payment amount: priceTotal cannot be split across member slots without rounding");
    }
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
