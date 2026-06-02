package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kz.hrms.splitupauth.dto.ConfirmPaymentRequest;
import kz.hrms.splitupauth.dto.CreatePaymentIntentRequest;
import kz.hrms.splitupauth.dto.PaymentIntentResponse;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.payment.gateway.GatewayChargeRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayChargeResponse;
import kz.hrms.splitupauth.payment.gateway.GatewayWebhookEvent;
import kz.hrms.splitupauth.payment.gateway.PaymentGateway;
import kz.hrms.splitupauth.payment.gateway.PaymentGatewayRegistry;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway;
import kz.hrms.splitupauth.repository.PaymentIntentRepository;
import kz.hrms.splitupauth.repository.PaymentTransactionRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.SavedCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private static final int MONEY_SCALE = 2;

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final SavedCardRepository savedCardRepository;
    private final RoomMemberService roomMemberService;
    private final PaymentGatewayRegistry gatewayRegistry;
    private final SavedCardService savedCardService;
    private final PaymentEventLogger eventLogger;
    private final PayoutService payoutService;
    private final RefundService refundService;
    private final RoomEventLogger roomEventLogger;

    @Transactional
    public PaymentIntentResponse createPaymentIntent(
            Long roomMemberId,
            User currentUser,
            CreatePaymentIntentRequest request
    ) {
        RoomMember roomMember = roomMemberRepository.findById(roomMemberId)
                .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));

        if (roomMember.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Membership not found");
        }

        if (!roomMember.getUser().getId().equals(currentUser.getId())) {
            throw new ForbiddenOperationException("You can only create payment intent for your own membership");
        }

        if (currentUser.getPhoneVerifiedAt() == null) {
            throw new ForbiddenOperationException("Verify your phone number before paying");
        }

        // Idempotency must be checked BEFORE the status guard: once a payment succeeds the
        // membership leaves APPLIED, and a retried call with the same key must still return
        // the original intent (not fail the status check → no double charge, true idempotency).
        PaymentIntent existing = paymentIntentRepository.findByIdempotencyKey(request.getIdempotencyKey())
                .orElse(null);
        if (existing != null) {
            return mapToResponse(existing);
        }

        if (roomMember.getStatus() != MemberStatus.APPLIED) {
            throw new InvalidRequestException("Payment intent can only be created for APPLIED membership");
        }

        PaymentGateway gateway = gatewayRegistry.defaultGateway();
        BigDecimal amount = resolvePaymentAmount(roomMember.getRoom());

        SavedCard savedCard = null;
        if (request.getSavedCardId() != null) {
            savedCard = savedCardRepository.findById(request.getSavedCardId())
                    .filter(c -> c.getUser().getId().equals(currentUser.getId()))
                    .filter(c -> c.getStatus() == SavedCardStatus.ACTIVE)
                    .orElseThrow(() -> new InvalidRequestException("Saved card not found or inactive"));
        }

        PaymentIntent intent = PaymentIntent.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .roomMember(roomMember)
                .user(currentUser)
                .amount(amount)
                .status(PaymentIntentStatus.PENDING)
                .providerName(gateway.providerName())
                .saveCardRequested(Boolean.TRUE.equals(request.getSaveCard()))
                .savedCard(savedCard)
                .expiresAt(LocalDateTime.now().plusMinutes(30))
                .build();
        intent = paymentIntentRepository.save(intent);

        eventLogger.log(
                "INTENT", intent.getId(), "CREATED",
                null, intent.getStatus().name(),
                currentUser.getId(), null, intent.getIdempotencyKey(),
                Map.of("provider", gateway.providerName(), "amount", amount.toPlainString())
        );

        GatewayChargeRequest chargeReq = GatewayChargeRequest.builder()
                .intentId(intent.getId())
                .idempotencyKey(intent.getIdempotencyKey())
                .amount(amount)
                .currency("KZT")
                .description("Ecopay membership #" + roomMember.getId())
                .userEmail(currentUser.getEmail())
                .userPhone(currentUser.getPhone())
                .saveCardRequested(intent.getSaveCardRequested())
                .build();

        GatewayChargeResponse chargeResp;
        try {
            chargeResp = savedCard != null
                    ? gateway.chargeWithToken(chargeReq, savedCard.getProviderToken())
                    : gateway.initCharge(chargeReq);
        } catch (Exception ex) {
            log.error("Gateway charge initiation failed for intent {}: {}", intent.getId(), ex.getMessage());
            intent.setStatus(PaymentIntentStatus.FAILED);
            intent.setFailureMessage("Gateway initiation failed: " + ex.getMessage());
            intent = paymentIntentRepository.save(intent);
            eventLogger.log("INTENT", intent.getId(), "GATEWAY_INIT_FAILED",
                    "PENDING", "FAILED", currentUser.getId(), null,
                    intent.getIdempotencyKey(), Map.of("error", ex.getMessage()));
            return mapToResponse(intent);
        }

        if (!chargeResp.isSuccess()) {
            intent.setStatus(PaymentIntentStatus.FAILED);
            intent.setProviderStatusCode(chargeResp.getProviderStatusCode());
            intent.setFailureCode(chargeResp.getFailureCode());
            intent.setFailureMessage(chargeResp.getFailureMessage());
        } else {
            intent.setExternalPaymentId(chargeResp.getExternalPaymentId());
            intent.setPaymentUrl(chargeResp.getPaymentUrl());
            intent.setProviderStatusCode(chargeResp.getProviderStatusCode());
            // A synchronous, non-redirect success finalizes immediately (saved-card
            // charges and the dev mock gateway). Real Freedom Pay init always requires
            // a redirect, so that path still waits for the webhook.
            if (!chargeResp.isRequiresRedirect()) {
                intent.setStatus(PaymentIntentStatus.SUCCESS);
            }
        }
        intent = paymentIntentRepository.save(intent);

        if (intent.getStatus() == PaymentIntentStatus.SUCCESS) {
            applySuccessfulCharge(intent, null, null);
        }

        return mapToResponse(intent);
    }

    /**
     * Single source of truth for the side effects of a successful charge:
     * record the transaction, advance the membership (idempotent), and create
     * the owner payout. Used by the initial intent flow, the webhook flow, and
     * recurring auto-charges.
     */
    @Transactional
    public void applySuccessfulCharge(PaymentIntent intent, String cardPanMask, String providerSignature) {
        recordSuccessTransaction(intent, cardPanMask, providerSignature);
        roomMemberService.markMembershipAsPaid(intent.getRoomMember());
        payoutService.createOwnerPayoutForSuccessfulPayment(intent);

        RoomMember member = intent.getRoomMember();
        roomEventLogger.log(
                member == null ? null : member.getRoom(), member, intent.getUser(), "MEMBER",
                "payment_success",
                Map.of("intentId", String.valueOf(intent.getId()),
                        "amount", String.valueOf(intent.getAmount())));
    }

    /**
     * Fails PENDING intents whose 30-minute window elapsed without a terminal
     * callback. Prevents stale intents from lingering forever (the user can then
     * safely retry). Returns the number of intents expired.
     */
    @Transactional
    public int expireStalePendingIntents() {
        var stale = paymentIntentRepository.findByStatusAndExpiresAtBefore(
                PaymentIntentStatus.PENDING, LocalDateTime.now());
        for (PaymentIntent intent : stale) {
            intent.setStatus(PaymentIntentStatus.FAILED);
            intent.setFailureCode("EXPIRED");
            intent.setFailureMessage("Payment was not completed before the intent expired");
            paymentIntentRepository.save(intent);
            eventLogger.log("INTENT", intent.getId(), "EXPIRED",
                    "PENDING", "FAILED", null, null, intent.getIdempotencyKey(),
                    Map.of("expiresAt", String.valueOf(intent.getExpiresAt())));
        }
        if (!stale.isEmpty()) {
            log.info("Expired {} stale PENDING payment intents", stale.size());
        }
        return stale.size();
    }

    @Transactional(readOnly = true)
    public PaymentIntentResponse getPaymentIntent(Long intentId, User currentUser) {
        PaymentIntent intent = paymentIntentRepository.findById(intentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment intent not found"));
        if (!intent.getUser().getId().equals(currentUser.getId())) {
            throw new ForbiddenOperationException("Not your payment intent");
        }
        return mapToResponse(intent);
    }

    @Transactional
    public PaymentIntentResponse confirmPaymentSuccess(
            Long paymentIntentId,
            User currentUser,
            ConfirmPaymentRequest request
    ) {
        // Webhook is the source of truth for SUCCESS/FAILED — this endpoint
        // is now treated as a redirect-back hint that returns current state.
        return getPaymentIntent(paymentIntentId, currentUser);
    }

    /**
     * Process a verified webhook event. Caller must have already saved the
     * inbox row (idempotency) and verified the signature.
     */
    @Transactional
    public void applyWebhookEvent(GatewayWebhookEvent event) {
        // Async payout result callback (no intent id) — route to the payout service.
        if ("PAYOUT".equals(event.getKind())) {
            payoutService.applyPayoutWebhook(
                    event.getExternalPaymentId(),
                    "SUCCESS".equals(event.getResultStatus()));
            return;
        }

        // Async refund result callback — route to the refund service.
        if ("REFUND".equals(event.getKind())) {
            refundService.applyRefundWebhook(
                    event.getExternalPaymentId(),
                    "SUCCESS".equals(event.getResultStatus()));
            return;
        }

        if (event.getIntentId() == null) {
            log.warn("Webhook event without intent id, ignoring");
            return;
        }

        PaymentIntent intent = paymentIntentRepository.findWithLockById(event.getIntentId())
                .orElse(null);
        if (intent == null) {
            log.warn("Webhook references unknown intent id {}", event.getIntentId());
            return;
        }

        intent.setLastWebhookAt(LocalDateTime.now());

        if (intent.getStatus() == PaymentIntentStatus.SUCCESS) {
            // SUCCESS is terminal; only audit the duplicate.
            eventLogger.log("INTENT", intent.getId(), "WEBHOOK_LATE_DUPLICATE",
                    intent.getStatus().name(), intent.getStatus().name(),
                    null, event.getProviderRequestId(), intent.getIdempotencyKey(),
                    Map.of("resultStatus", String.valueOf(event.getResultStatus())));
            paymentIntentRepository.save(intent);
            return;
        }

        if ("SUCCESS".equals(event.getResultStatus())) {
            // Defence-in-depth: never trust a SUCCESS callback whose amount/currency
            // does not match the intent we created. The signature already covers the
            // amount, but a mismatch means tampering or a provider bug → treat as FAILED.
            if (event.getAmount() != null
                    && intent.getAmount().compareTo(event.getAmount()) != 0) {
                log.error("Webhook amount mismatch for intent {}: expected {} got {} — rejecting",
                        intent.getId(), intent.getAmount(), event.getAmount());
                intent.setStatus(PaymentIntentStatus.FAILED);
                intent.setFailureCode("AMOUNT_MISMATCH");
                intent.setFailureMessage("Callback amount " + event.getAmount()
                        + " does not match intent amount " + intent.getAmount());
                paymentIntentRepository.save(intent);
                eventLogger.log("INTENT", intent.getId(), "WEBHOOK_AMOUNT_MISMATCH",
                        "PENDING", "FAILED", null, event.getProviderRequestId(),
                        intent.getIdempotencyKey(),
                        Map.of("expected", intent.getAmount().toPlainString(),
                                "received", event.getAmount().toPlainString()));
                return;
            }
            if (event.getCurrency() != null && !event.getCurrency().isBlank()
                    && !"KZT".equalsIgnoreCase(event.getCurrency())) {
                log.error("Webhook currency mismatch for intent {}: got {} — rejecting",
                        intent.getId(), event.getCurrency());
                intent.setStatus(PaymentIntentStatus.FAILED);
                intent.setFailureCode("CURRENCY_MISMATCH");
                intent.setFailureMessage("Callback currency " + event.getCurrency() + " is not KZT");
                paymentIntentRepository.save(intent);
                return;
            }
            String fromStatus = intent.getStatus().name();
            intent.setStatus(PaymentIntentStatus.SUCCESS);
            intent.setExternalPaymentId(event.getExternalPaymentId());
            intent.setProviderStatusCode(event.getProviderStatusCode());
            intent = paymentIntentRepository.save(intent);

            if (Boolean.TRUE.equals(intent.getSaveCardRequested())
                    && event.getCardToken() != null && !event.getCardToken().isBlank()) {
                savedCardService.upsertSavedCard(
                        intent.getUser(),
                        FreedomPayGateway.PROVIDER_NAME,
                        event.getCardToken(),
                        event.getCardPanMask()
                );
            }

            applySuccessfulCharge(intent, event.getCardPanMask(), event.getSignature());

            eventLogger.log("INTENT", intent.getId(), "WEBHOOK_SUCCESS",
                    fromStatus, intent.getStatus().name(),
                    null, event.getProviderRequestId(), intent.getIdempotencyKey(),
                    Map.of("externalPaymentId", String.valueOf(event.getExternalPaymentId())));
        } else if ("FAILED".equals(event.getResultStatus())) {
            String fromStatus = intent.getStatus().name();
            intent.setStatus(PaymentIntentStatus.FAILED);
            intent.setExternalPaymentId(event.getExternalPaymentId());
            intent.setProviderStatusCode(event.getProviderStatusCode());
            intent.setFailureCode(event.getFailureCode());
            intent.setFailureMessage(event.getFailureMessage());
            paymentIntentRepository.save(intent);

            eventLogger.log("INTENT", intent.getId(), "WEBHOOK_FAILED",
                    fromStatus, intent.getStatus().name(),
                    null, event.getProviderRequestId(), intent.getIdempotencyKey(),
                    Map.of("failureCode", String.valueOf(event.getFailureCode()),
                            "failureMessage", String.valueOf(event.getFailureMessage())));
        } else {
            log.info("Webhook with non-terminal status {}, ignoring", event.getResultStatus());
        }
    }

    private void recordSuccessTransaction(PaymentIntent intent, String cardPanMask, String providerSignature) {
        ObjectNode rawPayload = JsonNodeFactory.instance.objectNode();
        rawPayload.put("provider", intent.getProviderName());
        rawPayload.put("paymentIntentId", intent.getId());
        rawPayload.put("roomMemberId", intent.getRoomMember().getId());
        rawPayload.put("externalPaymentId", String.valueOf(intent.getExternalPaymentId()));

        PaymentTransaction tx = PaymentTransaction.builder()
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
        paymentTransactionRepository.save(tx);
    }

    private PaymentIntentResponse mapToResponse(PaymentIntent intent) {
        return PaymentIntentResponse.builder()
                .id(intent.getId())
                .idempotencyKey(intent.getIdempotencyKey())
                .amount(intent.getAmount())
                .currency("KZT")
                .status(intent.getStatus())
                .providerName(intent.getProviderName())
                .externalPaymentId(intent.getExternalPaymentId())
                .roomMemberId(intent.getRoomMember().getId())
                .paymentUrl(intent.getPaymentUrl())
                .requiresRedirect(intent.getPaymentUrl() != null
                        && intent.getStatus() == PaymentIntentStatus.PENDING)
                .saveCardRequested(intent.getSaveCardRequested())
                .failureCode(intent.getFailureCode())
                .failureMessage(intent.getFailureMessage())
                .build();
    }

    private BigDecimal resolvePaymentAmount(Room room) {
        if (room == null) {
            throw new InvalidRequestException("Room configuration is required to calculate payment amount");
        }

        if (isPositiveAmount(room.getPricePerMember())) {
            return normalizeMoneyAmount(
                    room.getPricePerMember(),
                    "Room pricePerMember"
            );
        }

        if (!isPositiveAmount(room.getPriceTotal())) {
            throw new InvalidRequestException(
                    "Cannot determine payment amount: room must have positive pricePerMember or positive priceTotal"
            );
        }

        int participantCount = resolveParticipantCount(room);

        try {
            BigDecimal share = room.getPriceTotal().divide(
                    BigDecimal.valueOf(participantCount),
                    MONEY_SCALE,
                    RoundingMode.UNNECESSARY
            );
            return normalizeMoneyAmount(
                    share,
                    "Calculated payment amount"
            );
        } catch (ArithmeticException ex) {
            throw new InvalidRequestException(
                    "Cannot determine payment amount: priceTotal cannot be split across member slots without rounding"
            );
        }
    }

    private int resolveParticipantCount(Room room) {
        Integer maxMembers = room.getMaxMembers();
        if (maxMembers == null || maxMembers < 2) {
            throw new InvalidRequestException(
                    "Cannot determine payment amount: room maxMembers must be at least 2"
            );
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
