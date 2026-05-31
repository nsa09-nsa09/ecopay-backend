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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PayoutService {

    private static final int MAX_RETRY = 3;

    private final PayoutRepository payoutRepository;
    private final PayoutMethodRepository payoutMethodRepository;
    private final PaymentGatewayRegistry gatewayRegistry;
    private final PaymentEventLogger eventLogger;
    private final SavedCardRepository savedCardRepository;

    @Value("${app.platform.fee-percent:8}")
    private int platformFeePercent;

    /**
     * Called from PaymentService when a member's charge succeeds. Creates a
     * pending payout for the room owner.
     */
    @Transactional
    public Payout createOwnerPayoutForSuccessfulPayment(PaymentIntent intent) {
        if (intent.getRoomMember() == null || intent.getRoomMember().getRoom() == null) {
            return null;
        }
        User owner = intent.getRoomMember().getRoom().getOwner();
        BigDecimal fee = intent.getAmount()
                .multiply(BigDecimal.valueOf(platformFeePercent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal payoutAmount = intent.getAmount().subtract(fee);

        Payout payout = Payout.builder()
                .user(owner)
                .room(intent.getRoomMember().getRoom())
                .triggeringPaymentIntent(intent)
                .amount(payoutAmount)
                .currency("KZT")
                .status("PENDING")
                .idempotencyKey("payout-" + intent.getId() + "-" + UUID.randomUUID())
                .build();
        payout = payoutRepository.save(payout);

        eventLogger.log("PAYOUT", payout.getId(), "CREATED",
                null, payout.getStatus(), null, null,
                payout.getIdempotencyKey(),
                java.util.Map.of("amount", payoutAmount.toPlainString()));

        return payout;
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

    @Transactional
    public void dispatchPayout(Long payoutId) {
        Payout payout = payoutRepository.findById(payoutId).orElse(null);
        if (payout == null) return;
        if (!"PENDING".equals(payout.getStatus()) && !"PENDING_METHOD".equals(payout.getStatus())) return;

        PayoutMethod method = payoutMethodRepository
                .findByUserAndIsDefaultTrueAndStatus(payout.getUser(), "ACTIVE")
                .orElse(null);
        if (method == null) {
            payout.setStatus("PENDING_METHOD");
            payoutRepository.save(payout);
            return;
        }

        if (payout.getRetryCount() != null && payout.getRetryCount() >= MAX_RETRY) {
            payout.setStatus("FAILED");
            payout.setFailureReason("Max retries exceeded");
            payoutRepository.save(payout);
            return;
        }

        payout.setStatus("PROCESSING");
        payout.setPayoutMethod(method);
        payout = payoutRepository.save(payout);

        try {
            GatewayPayoutResponse resp = gatewayRegistry.defaultGateway().payout(
                    GatewayPayoutRequest.builder()
                            .payoutId(payout.getId())
                            .idempotencyKey(payout.getIdempotencyKey())
                            .destinationCardToken(method.getProviderCardToken())
                            .amount(payout.getAmount())
                            .currency(payout.getCurrency())
                            .description("Ecopay payout #" + payout.getId())
                            .build()
            );

            if (resp.isSuccess()) {
                payout.setStatus("SUCCESS");
                payout.setProviderPayoutId(resp.getExternalPayoutId());
                payout.setProcessedAt(LocalDateTime.now());
            } else if (resp.isPending()) {
                payout.setStatus("PROCESSING");
                payout.setProviderPayoutId(resp.getExternalPayoutId());
            } else {
                payout.setRetryCount((payout.getRetryCount() == null ? 0 : payout.getRetryCount()) + 1);
                payout.setFailureReason(resp.getFailureMessage());
                payout.setStatus(payout.getRetryCount() >= MAX_RETRY ? "FAILED" : "PENDING");
            }
        } catch (Exception ex) {
            payout.setRetryCount((payout.getRetryCount() == null ? 0 : payout.getRetryCount()) + 1);
            payout.setFailureReason(ex.getMessage());
            payout.setStatus(payout.getRetryCount() >= MAX_RETRY ? "FAILED" : "PENDING");
        }
        payoutRepository.save(payout);
    }

    /**
     * Apply an async payout result callback from the provider. Confirms a PROCESSING
     * payout as SUCCESS/FAILED by its provider payout id. Idempotent: ignores callbacks
     * for unknown or already-terminal payouts. (Used by the prod Freedom Pay flow; the
     * dev mock settles payouts synchronously and never sends this callback.)
     */
    @Transactional
    public void applyPayoutWebhook(String providerPayoutId, boolean success) {
        if (providerPayoutId == null || providerPayoutId.isBlank()) {
            log.warn("Payout webhook without provider payout id, ignoring");
            return;
        }
        Payout payout = payoutRepository.findByProviderPayoutId(providerPayoutId).orElse(null);
        if (payout == null) {
            log.warn("Payout webhook references unknown provider payout id {}", providerPayoutId);
            return;
        }
        if ("SUCCESS".equals(payout.getStatus()) || "FAILED".equals(payout.getStatus())) {
            return; // terminal — idempotent no-op
        }
        payout.setStatus(success ? "SUCCESS" : "FAILED");
        if (!success) {
            payout.setFailureReason("Provider reported payout failure");
        }
        payout.setProcessedAt(LocalDateTime.now());
        payoutRepository.save(payout);
        log.info("Payout {} marked {} by provider callback", payout.getId(), payout.getStatus());
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
