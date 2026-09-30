package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import kz.hrms.splitupauth.repository.PaymentTransactionRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.SavedCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Payment intents and captures.
 *
 * <p><b>Concurrency contract.</b> Every decision that consumes a seat runs under
 * the room row lock, and locks are always taken in the order
 * room &rarr; membership &rarr; payment intent, so concurrent joins, payments and
 * provider callbacks for one room serialize without deadlocking. The database
 * backs each rule with a constraint (one PENDING intent per membership, one
 * CHARGE per intent, one payout per intent, seat-capacity trigger).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private static final int MONEY_SCALE = 2;
    private static final List<MemberStatus> OCCUPYING_STATUSES = List.of(MemberStatus.PENDING, MemberStatus.ACTIVE);

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
    private final RoomRepository roomRepository;

    private TransactionTemplate txTemplate;

    @Autowired
    void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /** What phase 1 of intent creation hands to the provider call. */
    private record IntentReservation(PaymentIntentResponse existing,
                                     Long intentId,
                                     String providerName,
                                     GatewayChargeRequest chargeRequest,
                                     String savedCardToken) {
        static IntentReservation replay(PaymentIntentResponse existing) {
            return new IntentReservation(existing, null, null, null, null);
        }
    }

    /**
     * Creates the seat payment for an APPLIED membership in three steps so no
     * DB lock is held during the provider HTTP call and a provider-side success
     * can never be lost to a rolled-back transaction:
     * <ol>
     *   <li>reserve: validate under room + membership locks, commit a PENDING intent
     *       (it holds the seat until it expires);</li>
     *   <li>call the provider with the committed intent id as the order id;</li>
     *   <li>record the outcome, unless a callback already finalized the intent.</li>
     * </ol>
     * Losing concurrent requests get a deterministic 409 (ROOM_FULL,
     * PAYMENT_IN_PROGRESS, IDEMPOTENCY_KEY_REUSED); retries with the same key
     * get the original intent back.
     */
    public PaymentIntentResponse createPaymentIntent(
            Long roomMemberId,
            User currentUser,
            CreatePaymentIntentRequest request
    ) {
        IntentReservation reservation = txTemplate.execute(s -> reserveIntent(roomMemberId, currentUser, request));
        if (reservation.existing() != null) {
            return reservation.existing();
        }

        PaymentGateway gateway = gatewayRegistry.resolve(reservation.providerName());
        GatewayChargeResponse chargeResp = null;
        Exception failure = null;
        try {
            chargeResp = reservation.savedCardToken() != null
                    ? gateway.chargeWithToken(reservation.chargeRequest(), reservation.savedCardToken())
                    : gateway.initCharge(reservation.chargeRequest());
        } catch (Exception ex) {
            failure = ex;
        }

        final GatewayChargeResponse resp = chargeResp;
        final Exception err = failure;
        return txTemplate.execute(s -> recordChargeInitiation(
                reservation.intentId(), resp, err, reservation.savedCardToken() != null, currentUser.getId()));
    }

    private IntentReservation reserveIntent(Long roomMemberId, User currentUser, CreatePaymentIntentRequest request) {
        Long roomId = roomMemberRepository.findRoomIdById(roomMemberId)
                .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));
        roomRepository.lockRoomRow(roomId);
        RoomMember roomMember = roomMemberRepository.findWithLockById(roomMemberId)
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
        // The membership lock above makes concurrent retries wait here and then see the row.
        PaymentIntent existing = paymentIntentRepository.findByIdempotencyKey(request.getIdempotencyKey())
                .orElse(null);
        if (existing != null) {
            if (!existing.getRoomMember().getId().equals(roomMemberId)
                    || !existing.getUser().getId().equals(currentUser.getId())) {
                throw new ResourceConflictException(
                        "IDEMPOTENCY_KEY_REUSED: this idempotency key belongs to a different payment");
            }
            return IntentReservation.replay(mapToResponse(existing));
        }

        if (roomMember.getStatus() != MemberStatus.APPLIED) {
            throw new InvalidRequestException("Payment intent can only be created for APPLIED membership");
        }

        Room room = roomMember.getRoom();
        if (room.getDeletedAt() != null
                || room.getStatus() == RoomStatus.CANCELLED
                || room.getStatus() == RoomStatus.BLOCKED
                || room.getStatus() == RoomStatus.COMPLETED) {
            throw new ResourceConflictException("ROOM_NOT_PAYABLE: room is " + room.getStatus());
        }

        LocalDateTime now = LocalDateTime.now();
        for (PaymentIntent pending : paymentIntentRepository.findByRoomMemberAndStatus(roomMember, PaymentIntentStatus.PENDING)) {
            if (pending.getExpiresAt() == null || pending.getExpiresAt().isAfter(now)) {
                // Same seat, same amount: a new click resumes the open provider order
                // (the user closed the payment page) instead of opening a second one.
                if (pending.getPaymentUrl() != null && request.getSavedCardId() == null) {
                    return IntentReservation.replay(mapToResponse(pending));
                }
                throw new ResourceConflictException(
                        "PAYMENT_IN_PROGRESS: payment " + pending.getId() + " for this membership is still awaiting completion");
            }
            // Abandoned: release it now so the partial unique index admits the new intent.
            markExpired(pending);
            paymentIntentRepository.saveAndFlush(pending);
        }

        long paidSeats = roomMemberRepository.countByRoomAndStatusInAndDeletedAtIsNull(room, OCCUPYING_STATUSES);
        long reservedSeats = paymentIntentRepository.countLiveSeatReservations(room.getId(), roomMember.getId(), now);
        if (paidSeats + reservedSeats >= room.getMaxMembers() - 1) {
            throw new ResourceConflictException("ROOM_FULL: no free seat in this room");
        }

        PaymentGateway gateway = gatewayRegistry.defaultGateway();
        BigDecimal amount = resolvePaymentAmount(room);

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
                .user(roomMember.getUser())
                .amount(amount)
                .status(PaymentIntentStatus.PENDING)
                .purpose(PaymentPurpose.INITIAL)
                .providerName(gateway.providerName())
                .saveCardRequested(Boolean.TRUE.equals(request.getSaveCard()))
                .savedCard(savedCard)
                .expiresAt(now.plusMinutes(30))
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

        return new IntentReservation(null, intent.getId(), gateway.providerName(), chargeReq,
                savedCard != null ? savedCard.getProviderToken() : null);
    }

    private PaymentIntentResponse recordChargeInitiation(Long intentId,
                                                         GatewayChargeResponse chargeResp,
                                                         Exception failure,
                                                         boolean tokenCharge,
                                                         Long actorUserId) {
        lockRoomOfIntent(intentId);
        PaymentIntent intent = paymentIntentRepository.findWithLockById(intentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment intent not found"));

        if (intent.getStatus() != PaymentIntentStatus.PENDING) {
            // A provider callback finalized the intent while we were waiting on the HTTP response.
            return mapToResponse(intent);
        }

        if (failure != null) {
            log.error("Gateway charge initiation failed for intent {}: {}", intent.getId(), failure.getMessage());
            if (tokenCharge) {
                // A saved-card debit may have been captured before the response was lost.
                // Keep the intent PENDING (the seat stays reserved) and let the provider
                // callback — keyed by the committed intent id — or expiry settle it.
                intent.setFailureCode("GATEWAY_OUTCOME_UNKNOWN");
                intent.setFailureMessage("Gateway response lost: " + failure.getMessage());
                intent = paymentIntentRepository.save(intent);
                eventLogger.log("INTENT", intent.getId(), "GATEWAY_OUTCOME_UNKNOWN",
                        "PENDING", "PENDING", actorUserId, null,
                        intent.getIdempotencyKey(), Map.of("error", String.valueOf(failure.getMessage())));
                return mapToResponse(intent);
            }
            intent.setStatus(PaymentIntentStatus.FAILED);
            intent.setFailureMessage("Gateway initiation failed: " + failure.getMessage());
            intent = paymentIntentRepository.save(intent);
            eventLogger.log("INTENT", intent.getId(), "GATEWAY_INIT_FAILED",
                    "PENDING", "FAILED", actorUserId, null,
                    intent.getIdempotencyKey(), Map.of("error", String.valueOf(failure.getMessage())));
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
     * record the transaction, advance the membership, and create the owner
     * payout. Idempotent per intent (backed by a unique index on the CHARGE
     * row). Callers must hold the room lock so the seat decision is race-free.
     *
     * <p>Money that was captured but cannot buy a seat (room filled up after
     * the reservation expired, a second capture for an already-paid seat, a
     * membership that is no longer payable) is still recorded, but credits
     * nobody: the intent is flagged {@code manual_review_reason} for refund and
     * no owner payable is created.
     */
    @Transactional
    public void applySuccessfulCharge(PaymentIntent intent, String cardPanMask, String providerSignature) {
        if (paymentTransactionRepository.existsByPaymentIntentAndType(intent, PaymentTransactionType.CHARGE)) {
            eventLogger.log("INTENT", intent.getId(), "CAPTURE_ALREADY_RECORDED",
                    intent.getStatus().name(), intent.getStatus().name(),
                    null, null, intent.getIdempotencyKey(), Map.of());
            return;
        }
        recordSuccessTransaction(intent, cardPanMask, providerSignature);

        RoomMember member = intent.getRoomMember();
        String reviewReason = captureReviewReason(intent, member);
        if (reviewReason != null) {
            intent.setManualReviewReason(reviewReason);
            paymentIntentRepository.save(intent);
            eventLogger.log("INTENT", intent.getId(), "CAPTURE_REQUIRES_REFUND",
                    intent.getStatus().name(), intent.getStatus().name(),
                    null, null, intent.getIdempotencyKey(),
                    Map.of("reason", reviewReason,
                            "memberStatus", String.valueOf(member == null ? null : member.getStatus())));
            log.error("Intent {} captured {} but cannot be credited ({}); flagged for refund review",
                    intent.getId(), intent.getAmount(), reviewReason);
            return;
        }

        if (intent.getPurpose() != PaymentPurpose.RECURRING) {
            member.setPaymentIntentId(intent.getId());
            roomMemberService.markMembershipAsPaid(member);
        }
        payoutService.createOwnerPayoutForSuccessfulPayment(intent);

        roomEventLogger.log(
                member.getRoom(), member, intent.getUser(), "MEMBER",
                "payment_success",
                Map.of("intentId", String.valueOf(intent.getId()),
                        "amount", String.valueOf(intent.getAmount())));
    }

    /** Why a capture cannot be credited to its membership, or null when it can. */
    private String captureReviewReason(PaymentIntent intent, RoomMember member) {
        if (member == null || member.getDeletedAt() != null) {
            return "MEMBERSHIP_GONE";
        }
        if (intent.getPurpose() == PaymentPurpose.RECURRING) {
            return member.getStatus() == MemberStatus.ACTIVE ? null : "MEMBERSHIP_NOT_ACTIVE";
        }
        MemberStatus status = member.getStatus();
        if (status == MemberStatus.PENDING || status == MemberStatus.ACTIVE) {
            // The seat was already bought by another intent of this membership.
            return "DUPLICATE_CAPTURE";
        }
        if (status != MemberStatus.APPLIED) {
            return "MEMBERSHIP_NOT_PAYABLE";
        }
        Room room = member.getRoom();
        if (room.getMaxMembers() != null) {
            long paidSeats = roomMemberRepository.countByRoomAndStatusInAndDeletedAtIsNull(room, OCCUPYING_STATUSES);
            if (paidSeats >= room.getMaxMembers() - 1) {
                return "NO_SEAT_AVAILABLE";
            }
        }
        return null;
    }

    /**
     * Fails PENDING intents whose 30-minute window elapsed without a terminal
     * callback. Each candidate is re-read under its row lock, so a SUCCESS that
     * a callback committed in the meantime is never overwritten. Returns the
     * number of intents expired.
     */
    @Transactional
    public int expireStalePendingIntents() {
        LocalDateTime now = LocalDateTime.now();
        List<Long> candidates = paymentIntentRepository.findIdsByStatusAndExpiresAtBefore(PaymentIntentStatus.PENDING, now);
        int expired = 0;
        for (Long id : candidates) {
            PaymentIntent intent = paymentIntentRepository.findWithLockById(id).orElse(null);
            if (intent == null
                    || intent.getStatus() != PaymentIntentStatus.PENDING
                    || intent.getExpiresAt() == null
                    || !intent.getExpiresAt().isBefore(now)) {
                continue;
            }
            markExpired(intent);
            paymentIntentRepository.save(intent);
            expired++;
        }
        if (expired > 0) {
            log.info("Expired {} stale PENDING payment intents", expired);
        }
        return expired;
    }

    private void markExpired(PaymentIntent intent) {
        intent.setStatus(PaymentIntentStatus.FAILED);
        intent.setFailureCode("EXPIRED");
        intent.setFailureMessage("Payment was not completed before the intent expired");
        eventLogger.log("INTENT", intent.getId(), "EXPIRED",
                "PENDING", "FAILED", null, null, intent.getIdempotencyKey(),
                Map.of("expiresAt", String.valueOf(intent.getExpiresAt())));
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

    /**
     * Redirect-back reconciliation. The async webhook (result.php) is the
     * primary source of truth, but it needs a publicly reachable URL. When the
     * user is redirected back from the hosted payment page we actively query the
     * gateway for the payment status and finalize if it already succeeded — this
     * makes the flow complete end-to-end even when the inbound webhook cannot
     * reach us (e.g. local dev without a tunnel). The status query runs without
     * holding any lock; the result is applied under the room + intent locks.
     */
    public PaymentIntentResponse confirmPaymentSuccess(
            Long paymentIntentId,
            User currentUser,
            ConfirmPaymentRequest request
    ) {
        String[] lookup = new String[2]; // [externalPaymentId, providerName]
        PaymentIntentResponse early = txTemplate.execute(s -> {
            PaymentIntent intent = paymentIntentRepository.findById(paymentIntentId)
                    .orElseThrow(() -> new ResourceNotFoundException("Payment intent not found"));
            if (!intent.getUser().getId().equals(currentUser.getId())) {
                throw new ForbiddenOperationException("Not your payment intent");
            }
            // Already terminal (e.g. the webhook arrived first), or never initiated at the gateway.
            if (intent.getStatus() != PaymentIntentStatus.PENDING
                    || intent.getExternalPaymentId() == null || intent.getExternalPaymentId().isBlank()) {
                return mapToResponse(intent);
            }
            lookup[0] = intent.getExternalPaymentId();
            lookup[1] = intent.getProviderName();
            return null;
        });
        if (early != null) {
            return early;
        }

        GatewayStatusResponse status;
        try {
            status = gatewayRegistry.resolve(lookup[1]).getStatus(lookup[0]);
        } catch (Exception ex) {
            log.warn("Status reconcile failed for intent {}: {}", paymentIntentId, ex.getMessage());
            status = null;
        }
        final GatewayStatusResponse reconciled = status;
        return txTemplate.execute(s -> applyReconciledStatus(paymentIntentId, reconciled, currentUser));
    }

    private PaymentIntentResponse applyReconciledStatus(Long paymentIntentId, GatewayStatusResponse status, User currentUser) {
        lockRoomOfIntent(paymentIntentId);
        PaymentIntent intent = paymentIntentRepository.findWithLockById(paymentIntentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment intent not found"));
        if (intent.getStatus() != PaymentIntentStatus.PENDING || status == null) {
            return mapToResponse(intent);
        }

        String mapped = status.getStatus() == null ? "PENDING" : status.getStatus();

        if ("SUCCESS".equals(mapped)) {
            String fromStatus = intent.getStatus().name();
            intent.setStatus(PaymentIntentStatus.SUCCESS);
            if (status.getExternalPaymentId() != null && !status.getExternalPaymentId().isBlank()) {
                intent.setExternalPaymentId(status.getExternalPaymentId());
            }
            intent.setProviderStatusCode(status.getProviderStatusCode());
            intent = paymentIntentRepository.save(intent);

            if (Boolean.TRUE.equals(intent.getSaveCardRequested())
                    && status.getCardToken() != null && !status.getCardToken().isBlank()) {
                savedCardService.upsertSavedCard(
                        intent.getUser(),
                        FreedomPayGateway.PROVIDER_NAME,
                        status.getCardToken(),
                        status.getCardPanMask()
                );
            }

            applySuccessfulCharge(intent, status.getCardPanMask(), null);

            eventLogger.log("INTENT", intent.getId(), "REDIRECT_RECONCILE_SUCCESS",
                    fromStatus, intent.getStatus().name(),
                    currentUser.getId(), null, intent.getIdempotencyKey(),
                    Map.of("externalPaymentId", String.valueOf(intent.getExternalPaymentId())));
        } else if ("FAILED".equals(mapped)) {
            String fromStatus = intent.getStatus().name();
            intent.setStatus(PaymentIntentStatus.FAILED);
            intent.setProviderStatusCode(status.getProviderStatusCode());
            intent.setFailureCode("GATEWAY_FAILED");
            intent.setFailureMessage("Gateway reported the payment as failed");
            intent = paymentIntentRepository.save(intent);

            eventLogger.log("INTENT", intent.getId(), "REDIRECT_RECONCILE_FAILED",
                    fromStatus, intent.getStatus().name(),
                    currentUser.getId(), null, intent.getIdempotencyKey(),
                    Map.of("providerStatus", String.valueOf(status.getProviderStatusCode())));
        }
        // else: still PENDING at the gateway — leave as-is; webhook/poll will finalize.

        return mapToResponse(intent);
    }

    /**
     * Process a verified webhook event. Caller must have already saved the
     * inbox row (idempotency) and verified the signature.
     *
     * <p>State rules: SUCCESS is final for a charge (later FAILED/PENDING/duplicate
     * SUCCESS callbacks are audited no-ops); PENDING never changes state; a
     * late SUCCESS after a local FAILED (e.g. expiry) is honoured because the
     * money really moved.
     */
    @Transactional
    public void applyWebhookEvent(GatewayWebhookEvent event) {
        // Async payout result callback (no intent id) — route to the payout service.
        if ("PAYOUT".equals(event.getKind())) {
            if (isNonTerminal(event)) {
                log.info("Payout webhook with non-terminal status {}, ignoring", event.getResultStatus());
                return;
            }
            payoutService.applyPayoutWebhook(
                    event.getExternalPaymentId(),
                    orderIdOf(event),
                    "SUCCESS".equals(event.getResultStatus()));
            return;
        }

        // Async refund result callback — route to the refund service.
        if ("REFUND".equals(event.getKind())) {
            if (isNonTerminal(event)) {
                log.info("Refund webhook with non-terminal status {}, ignoring", event.getResultStatus());
                return;
            }
            refundService.applyRefundWebhook(
                    event.getExternalPaymentId(),
                    "SUCCESS".equals(event.getResultStatus()));
            return;
        }

        if (event.getIntentId() == null) {
            log.warn("Webhook event without intent id, ignoring");
            return;
        }

        lockRoomOfIntent(event.getIntentId());
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

    private static boolean isNonTerminal(GatewayWebhookEvent event) {
        return !"SUCCESS".equals(event.getResultStatus()) && !"FAILED".equals(event.getResultStatus());
    }

    /** Our immutable order reference echoed by the provider (pg_order_id), if present. */
    private static Long orderIdOf(GatewayWebhookEvent event) {
        if (event.getRawParams() == null) {
            return null;
        }
        String raw = event.getRawParams().get("pg_order_id");
        try {
            return raw == null || raw.isBlank() ? null : Long.valueOf(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Takes the room row lock first, preserving the room → membership → intent lock order. */
    private void lockRoomOfIntent(Long intentId) {
        paymentIntentRepository.findRoomIdById(intentId).ifPresent(roomRepository::lockRoomRow);
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
