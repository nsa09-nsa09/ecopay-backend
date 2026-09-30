package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.entity.Payout;
import kz.hrms.splitupauth.entity.PayoutMethod;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.SavedCardStatus;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.payment.gateway.GatewayPayoutRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayPayoutResponse;
import kz.hrms.splitupauth.payment.gateway.PaymentGatewayRegistry;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway;
import kz.hrms.splitupauth.repository.PayoutMethodRepository;
import kz.hrms.splitupauth.repository.PayoutRepository;
import kz.hrms.splitupauth.repository.SavedCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owner payables and their dispatch to the provider.
 *
 * <p><b>No double payout.</b> A payout is sent only by the transaction that
 * moves it PENDING &rarr; PROCESSING under its row lock (the "claim"); the claim
 * is committed before the provider is called, and the provider receives the
 * payout id as its immutable order reference. If the provider's answer is
 * lost the payout becomes UNKNOWN and is settled by the provider callback or
 * an operator — it is never re-sent automatically. Refund reversal takes the
 * same row lock, so a payout is either reversed before the claim or flagged
 * for clawback after it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PayoutService {

    private static final int MAX_RETRY = 3;
    /** A claim this old with no recorded provider answer means the dispatcher died mid-flight. */
    private static final long STUCK_CLAIM_MINUTES = 15;
    private static final Set<String> NOT_YET_DISPATCHED = Set.of("PENDING", "PENDING_METHOD");
    private static final Set<String> CLOSED_UNPAID = Set.of("REVERSED", "FAILED", "CANCELED");

    private final PayoutRepository payoutRepository;
    private final PayoutMethodRepository payoutMethodRepository;
    private final PaymentGatewayRegistry gatewayRegistry;
    private final PaymentEventLogger eventLogger;
    private final SavedCardRepository savedCardRepository;

    @Value("${app.platform.fee-percent:8}")
    private int platformFeePercent;

    private TransactionTemplate txTemplate;

    @Autowired
    void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Called from PaymentService when a member's charge succeeds. Creates the
     * pending payout for the room owner — at most one per captured payment
     * (unique index on triggering_payment_intent_id), so a replayed capture
     * returns the existing payable instead of creating a second one.
     */
    @Transactional
    public Payout createOwnerPayoutForSuccessfulPayment(PaymentIntent intent) {
        if (intent.getRoomMember() == null || intent.getRoomMember().getRoom() == null) {
            return null;
        }
        Payout existing = payoutRepository.findByTriggeringPaymentIntent(intent).orElse(null);
        if (existing != null) {
            return existing;
        }
        User owner = intent.getRoomMember().getRoom().getOwner();
        BigDecimal payoutAmount = ownerShare(intent.getAmount());

        Payout payout = Payout.builder()
                .user(owner)
                .room(intent.getRoomMember().getRoom())
                .triggeringPaymentIntent(intent)
                .amount(payoutAmount)
                .currency("KZT")
                .status("PENDING")
                // Deterministic: the same captured payment always maps to the same payable.
                .idempotencyKey("payout-intent-" + intent.getId())
                .build();
        payout = payoutRepository.save(payout);

        eventLogger.log("PAYOUT", payout.getId(), "CREATED",
                null, payout.getStatus(), null, null,
                payout.getIdempotencyKey(),
                Map.of("amount", payoutAmount.toPlainString()));

        return payout;
    }

    /** Owner's part of money the platform retained: retained minus the platform fee. */
    BigDecimal ownerShare(BigDecimal retained) {
        BigDecimal fee = retained
                .multiply(BigDecimal.valueOf(platformFeePercent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return retained.subtract(fee);
    }

    /** Run every minute: pick up PENDING payouts and try to dispatch them. */
    @Scheduled(fixedDelay = 60_000)
    public void processPendingPayouts() {
        List<Payout> pending = payoutRepository.findByStatusInOrderByCreatedAtAsc(
                List.of("PENDING", "PENDING_METHOD"));
        for (Payout payout : pending) {
            try {
                dispatchPayout(payout.getId());
            } catch (Exception ex) {
                log.error("Payout {} dispatch failed: {}", payout.getId(), ex.getMessage());
            }
        }
    }

    /** What the claim hands to the provider call (read after the claim, so never stale). */
    private record DispatchClaim(GatewayPayoutRequest request) {}

    public void dispatchPayout(Long payoutId) {
        DispatchClaim claim = txTemplate.execute(s -> claimForDispatch(payoutId));
        if (claim == null) {
            return;
        }

        GatewayPayoutResponse resp = null;
        Exception failure = null;
        try {
            resp = gatewayRegistry.defaultGateway().payout(claim.request());
        } catch (Exception ex) {
            failure = ex;
        }

        final GatewayPayoutResponse response = resp;
        final Exception error = failure;
        txTemplate.executeWithoutResult(s -> recordDispatchResult(payoutId, response, error));
    }

    private DispatchClaim claimForDispatch(Long payoutId) {
        Payout payout = payoutRepository.findWithLockById(payoutId).orElse(null);
        if (payout == null || !NOT_YET_DISPATCHED.contains(payout.getStatus())) {
            return null;
        }

        PayoutMethod method = payoutMethodRepository
                .findByUserAndIsDefaultTrueAndStatus(payout.getUser(), "ACTIVE")
                .orElse(null);
        if (method == null) {
            payout.setStatus("PENDING_METHOD");
            payoutRepository.save(payout);
            return null;
        }

        if (payout.getRetryCount() != null && payout.getRetryCount() >= MAX_RETRY) {
            payout.setStatus("FAILED");
            payout.setFailureReason("Max retries exceeded");
            payoutRepository.save(payout);
            return null;
        }

        String from = payout.getStatus();
        payout.setStatus("PROCESSING");
        payout.setPayoutMethod(method);
        payoutRepository.save(payout);
        eventLogger.log("PAYOUT", payout.getId(), "DISPATCH_CLAIMED",
                from, "PROCESSING", null, null, payout.getIdempotencyKey(),
                Map.of("amount", payout.getAmount().toPlainString()));

        return new DispatchClaim(GatewayPayoutRequest.builder()
                .payoutId(payout.getId())
                .idempotencyKey(payout.getIdempotencyKey())
                .destinationCardToken(method.getProviderCardToken())
                .amount(payout.getAmount())
                .currency(payout.getCurrency())
                .description("Ecopay payout #" + payout.getId())
                .build());
    }

    private void recordDispatchResult(Long payoutId, GatewayPayoutResponse resp, Exception failure) {
        Payout payout = payoutRepository.findWithLockById(payoutId).orElse(null);
        if (payout == null) {
            return;
        }
        if (!"PROCESSING".equals(payout.getStatus())) {
            // The provider callback settled it while we waited for the HTTP answer.
            if (resp != null && resp.getExternalPayoutId() != null && payout.getProviderPayoutId() == null) {
                payout.setProviderPayoutId(resp.getExternalPayoutId());
                payoutRepository.save(payout);
            }
            return;
        }

        if (failure != null) {
            // Timeout / connection reset AFTER the request left us: the provider may
            // have paid. Re-sending could pay twice, so park it for reconciliation.
            payout.setStatus("UNKNOWN");
            payout.setFailureReason("Provider outcome unknown: " + failure.getMessage());
            eventLogger.log("PAYOUT", payout.getId(), "OUTCOME_UNKNOWN",
                    "PROCESSING", "UNKNOWN", null, null, payout.getIdempotencyKey(),
                    Map.of("error", String.valueOf(failure.getMessage())));
            log.error("Payout {} outcome unknown ({}); awaiting provider callback / manual reconciliation",
                    payout.getId(), failure.getMessage());
        } else if (resp.isSuccess()) {
            payout.setStatus("SUCCESS");
            payout.setProviderPayoutId(resp.getExternalPayoutId());
            payout.setProcessedAt(LocalDateTime.now());
        } else if (resp.isPending()) {
            payout.setProviderPayoutId(resp.getExternalPayoutId());
        } else {
            // Explicit rejection: nothing was paid, a retry with the same order id is safe.
            payout.setRetryCount((payout.getRetryCount() == null ? 0 : payout.getRetryCount()) + 1);
            payout.setFailureReason(resp.getFailureMessage());
            payout.setStatus(payout.getRetryCount() >= MAX_RETRY ? "FAILED" : "PENDING");
        }
        payoutRepository.save(payout);
    }

    /**
     * Payouts claimed long ago with no provider answer recorded (the process
     * died between the claim and the result) become UNKNOWN: visible for
     * reconciliation, never re-sent automatically.
     */
    @Scheduled(fixedDelayString = "${app.payouts.reconcile-delay-ms:300000}")
    @Transactional
    public int reconcileStuckPayouts() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(STUCK_CLAIM_MINUTES);
        int parked = 0;
        for (Long id : payoutRepository.findStuckProcessingIds(cutoff)) {
            Payout payout = payoutRepository.findWithLockById(id).orElse(null);
            if (payout == null || !"PROCESSING".equals(payout.getStatus()) || payout.getProviderPayoutId() != null) {
                continue;
            }
            payout.setStatus("UNKNOWN");
            payout.setFailureReason("Dispatch interrupted before the provider answer was recorded");
            payoutRepository.save(payout);
            eventLogger.log("PAYOUT", payout.getId(), "OUTCOME_UNKNOWN",
                    "PROCESSING", "UNKNOWN", null, null, payout.getIdempotencyKey(),
                    Map.of("reason", "stuck_claim"));
            parked++;
        }
        if (parked > 0) {
            log.warn("Parked {} interrupted payout dispatches as UNKNOWN", parked);
        }
        return parked;
    }

    /** Backwards-compatible entry point: provider payout id only. */
    @Transactional
    public void applyPayoutWebhook(String providerPayoutId, boolean success) {
        applyPayoutWebhook(providerPayoutId, null, success);
    }

    /**
     * Apply an async payout result callback. The payout is matched by the
     * provider payout id, or — when our process never recorded that id (lost
     * response, crash) — by our immutable order reference (payout id).
     * SUCCESS is final: a later FAILED never downgrades it; a SUCCESS for a
     * payout we had closed as unpaid is recorded (the money moved) and flagged.
     */
    @Transactional
    public void applyPayoutWebhook(String providerPayoutId, Long payoutOrderId, boolean success) {
        Payout payout = null;
        if (providerPayoutId != null && !providerPayoutId.isBlank()) {
            payout = payoutRepository.findByProviderPayoutId(providerPayoutId).orElse(null);
        }
        if (payout == null && payoutOrderId != null) {
            payout = payoutRepository.findWithLockById(payoutOrderId)
                    .filter(p -> p.getProviderPayoutId() == null || p.getProviderPayoutId().equals(providerPayoutId))
                    .orElse(null);
        }
        if (payout == null) {
            log.warn("Payout webhook references unknown payout (provider id {}, order id {})",
                    providerPayoutId, payoutOrderId);
            return;
        }

        String from = payout.getStatus();
        if ("SUCCESS".equals(from)) {
            return; // final — idempotent no-op
        }
        if (!success) {
            if (!"PROCESSING".equals(from) && !"UNKNOWN".equals(from)) {
                return; // nothing in flight to fail
            }
            payout.setStatus("FAILED");
            payout.setFailureReason("Provider reported payout failure");
            payout.setProcessedAt(LocalDateTime.now());
            payoutRepository.save(payout);
            log.info("Payout {} marked FAILED by provider callback", payout.getId());
            return;
        }

        if (payout.getProviderPayoutId() == null && providerPayoutId != null && !providerPayoutId.isBlank()) {
            payout.setProviderPayoutId(providerPayoutId);
        }
        payout.setStatus("SUCCESS");
        payout.setProcessedAt(LocalDateTime.now());
        payoutRepository.save(payout);
        if (!"PROCESSING".equals(from) && !"UNKNOWN".equals(from)) {
            eventLogger.log("PAYOUT", payout.getId(), "CLAWBACK_REQUIRED",
                    from, "SUCCESS", null, null, payout.getIdempotencyKey(),
                    Map.of("reason", "provider_paid_payout_closed_as_" + from.toLowerCase()));
            log.error("Payout {} was {} locally but the provider reports it PAID — manual review", payout.getId(), from);
        }
        log.info("Payout {} marked SUCCESS by provider callback", payout.getId());
    }

    /**
     * Refund hook with exact amounts: {@code retained} is what the platform
     * still holds of the triggering charge after successful refunds.
     * <ul>
     *   <li>not yet dispatched: the payable shrinks to the owner's share of the
     *       retained money, or is REVERSED when nothing is retained;</li>
     *   <li>dispatched / paid / outcome unknown: money may be with the owner —
     *       recorded as CLAWBACK_REQUIRED for manual recovery (unchanged policy).</li>
     * </ul>
     */
    @Transactional
    public void adjustOwnerPayoutForRefund(PaymentIntent triggeringIntent, BigDecimal retained) {
        if (triggeringIntent == null) {
            return;
        }
        Payout payout = payoutRepository.findByTriggeringPaymentIntent(triggeringIntent).orElse(null);
        if (payout == null) {
            return;
        }
        String status = payout.getStatus();
        BigDecimal stillRetained = retained == null || retained.signum() < 0 ? BigDecimal.ZERO : retained;

        if (NOT_YET_DISPATCHED.contains(status)) {
            if (stillRetained.signum() == 0) {
                payout.setStatus("REVERSED");
                payout.setFailureReason("Reversed: triggering charge was refunded before payout");
                payout.setProcessedAt(LocalDateTime.now());
                payoutRepository.save(payout);
                eventLogger.log("PAYOUT", payout.getId(), "REVERSED",
                        status, "REVERSED", null, null, payout.getIdempotencyKey(),
                        Map.of("reason", "charge_refunded"));
                log.info("Payout {} reversed (charge refunded before payout)", payout.getId());
                return;
            }
            BigDecimal reduced = ownerShare(stillRetained);
            if (reduced.compareTo(payout.getAmount()) < 0) {
                BigDecimal old = payout.getAmount();
                payout.setAmount(reduced);
                payoutRepository.save(payout);
                eventLogger.log("PAYOUT", payout.getId(), "REDUCED",
                        status, status, null, null, payout.getIdempotencyKey(),
                        Map.of("from", old.toPlainString(), "to", reduced.toPlainString(),
                                "reason", "partial_refund"));
            }
            return;
        }
        if (CLOSED_UNPAID.contains(status)) {
            return; // the owner was never paid from this payable
        }
        flagClawback(payout, stillRetained.signum() == 0);
    }

    /**
     * Clawback hook without amounts (kept for callers that only know whether
     * the refund was full). A full refund is exact; a partial one cannot be
     * recomputed here and is flagged for review.
     */
    @Transactional
    public void reverseOwnerPayoutForRefund(PaymentIntent triggeringIntent, boolean fullRefund) {
        if (triggeringIntent == null) {
            return;
        }
        if (fullRefund) {
            adjustOwnerPayoutForRefund(triggeringIntent, BigDecimal.ZERO);
            return;
        }
        Payout payout = payoutRepository.findByTriggeringPaymentIntent(triggeringIntent).orElse(null);
        if (payout != null) {
            flagClawback(payout, false);
        }
    }

    private void flagClawback(Payout payout, boolean fullRefund) {
        eventLogger.log("PAYOUT", payout.getId(), "CLAWBACK_REQUIRED",
                payout.getStatus(), payout.getStatus(), null, null, payout.getIdempotencyKey(),
                Map.of("fullRefund", String.valueOf(fullRefund)));
        log.warn("Payout {} (status {}) needs manual clawback — its charge was refunded (full={})",
                payout.getId(), payout.getStatus(), fullRefund);
    }

    @Transactional(readOnly = true)
    public List<Payout> listMine(User user) {
        return payoutRepository.findByUserOrderByCreatedAtDesc(user);
    }

    @Transactional(readOnly = true)
    public Payout getMine(User user, Long id) {
        Payout p = payoutRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payout not found"));
        if (!p.getUser().getId().equals(user.getId())) {
            throw new ForbiddenOperationException("Not your payout");
        }
        return p;
    }

    @Transactional
    public PayoutMethod registerMethod(User user, String providerCardToken, String panMask) {
        if (providerCardToken == null || providerCardToken.isBlank()) {
            throw new InvalidRequestException("providerCardToken is required");
        }
        // Anti-IDOR: a payout method may only be registered from a card token the user
        // actually owns (one of their saved cards). Prevents registering someone else's
        // card token as a payout destination.
        savedCardRepository
                .findByUserAndProviderTokenAndProviderName(
                        user, providerCardToken, FreedomPayGateway.PROVIDER_NAME)
                .filter(c -> c.getStatus() == SavedCardStatus.ACTIVE)
                .orElseThrow(() -> new InvalidRequestException(
                        "Card token does not belong to you or is not an active saved card"));

        boolean firstMethod = payoutMethodRepository
                .findByUserAndIsDefaultTrueAndStatus(user, "ACTIVE").isEmpty();
        PayoutMethod method = PayoutMethod.builder()
                .user(user)
                .providerName(FreedomPayGateway.PROVIDER_NAME)
                .providerCardToken(providerCardToken)
                .panMask(panMask)
                .isDefault(firstMethod)
                .status("ACTIVE")
                .build();
        return payoutMethodRepository.save(method);
    }

    @Transactional(readOnly = true)
    public List<PayoutMethod> listMethods(User user) {
        return payoutMethodRepository
                .findByUserAndStatusOrderByIsDefaultDescCreatedAtDesc(user, "ACTIVE");
    }

    @Transactional
    public void revokeMethod(User user, Long id) {
        PayoutMethod method = payoutMethodRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payout method not found"));
        if (!method.getUser().getId().equals(user.getId())) {
            throw new ForbiddenOperationException("Not your method");
        }
        method.setStatus("REVOKED");
        method.setIsDefault(false);
        method.setRevokedAt(LocalDateTime.now());
        payoutMethodRepository.save(method);
    }
}
