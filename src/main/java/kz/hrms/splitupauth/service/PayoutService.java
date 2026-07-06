package kz.hrms.splitupauth.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.Payout;
import kz.hrms.splitupauth.entity.PayoutMethod;
import kz.hrms.splitupauth.entity.SavedCardStatus;
import kz.hrms.splitupauth.entity.User;
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
  private final NotificationService notificationService;

  /**
   * Days a captured payment is held in the merchant balance before the owner payout is dispatched.
   */
  @Value("${app.payout.hold-days:30}")
  private int payoutHoldDays;

  /**
   * Called from PaymentService when a member's charge succeeds. Creates a pending payout for the
   * room owner.
   */
  @Transactional
  public Payout createOwnerPayoutForSuccessfulPayment(PaymentIntent intent) {
    if (intent.getRoomMember() == null || intent.getRoomMember().getRoom() == null) {
      return null;
    }
    User owner = intent.getRoomMember().getRoom().getOwner();
    // The member was charged (share + commission). The owner is paid the full share;
    // ECOpay keeps the commission. Owners never pay a commission themselves.
    BigDecimal commission =
        intent.getCommissionAmount() == null ? BigDecimal.ZERO : intent.getCommissionAmount();
    BigDecimal payoutAmount =
        intent.getAmount().subtract(commission).setScale(2, RoundingMode.HALF_UP);

    // Hold the payout: capture happened now, but the owner is only paid once the hold
    // window elapses. The dispatcher skips payouts until releaseAt is reached.
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime releaseAt = now.plusDays(payoutHoldDays);

    Payout payout =
        Payout.builder()
            .user(owner)
            .room(intent.getRoomMember().getRoom())
            .triggeringPaymentIntent(intent)
            .amount(payoutAmount)
            .currency("KZT")
            .status("PENDING")
            .releaseAt(releaseAt)
            .idempotencyKey("payout-" + intent.getId() + "-" + UUID.randomUUID())
            .build();
    payout = payoutRepository.save(payout);

    eventLogger.log(
        "PAYOUT",
        payout.getId(),
        "CREATED",
        null,
        payout.getStatus(),
        null,
        null,
        payout.getIdempotencyKey(),
        java.util.Map.of(
            "amount",
            payoutAmount.toPlainString(),
            "commission",
            commission.toPlainString(),
            "releaseAt",
            releaseAt.toString(),
            "holdDays",
            String.valueOf(payoutHoldDays)));

    return payout;
  }

  /**
   * Run every minute: pick up payouts whose hold window has elapsed (releaseAt &lt;= now) and try
   * to dispatch them. Held payouts (releaseAt in the future) are skipped until due.
   */
  @Scheduled(fixedDelay = 60_000)
  public void processPendingPayouts() {
    List<Payout> pending =
        payoutRepository.findDispatchable(
            List.of("PENDING", "PENDING_METHOD"), LocalDateTime.now());
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
    if (!"PENDING".equals(payout.getStatus()) && !"PENDING_METHOD".equals(payout.getStatus()))
      return;

    PayoutMethod method =
        payoutMethodRepository
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
      GatewayPayoutResponse resp =
          gatewayRegistry
              .defaultGateway()
              .payout(
                  GatewayPayoutRequest.builder()
                      .payoutId(payout.getId())
                      .idempotencyKey(payout.getIdempotencyKey())
                      .destinationCardToken(method.getProviderCardToken())
                      .amount(payout.getAmount())
                      .currency(payout.getCurrency())
                      .description("Ecopay payout #" + payout.getId())
                      .build());

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
   * Apply an async payout result callback from the provider. Confirms a PROCESSING payout as
   * SUCCESS/FAILED by its provider payout id. Idempotent: ignores callbacks for unknown or
   * already-terminal payouts. (Used by the prod Freedom Pay flow; the dev mock settles payouts
   * synchronously and never sends this callback.)
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

    if (success && payout.getUser() != null) {
      notificationService.notify(
          payout.getUser(),
          kz.hrms.splitupauth.entity.NotificationType.PAYOUT_SENT,
          "Выплата отправлена",
          "Выплата на сумму "
              + payout.getAmount()
              + " "
              + payout.getCurrency()
              + " была отправлена на ваш способ получения.",
          "/payment/payout",
          java.util.Map.of("payoutId", payout.getId()));
    }
  }

  /**
   * Clawback hook: called when a charge is refunded. If the owner payout that the charge triggered
   * has not been paid out yet (PENDING/PENDING_METHOD) and the charge was fully refunded, reverse
   * the payout so the platform never pays out refunded money. If the payout was already dispatched
   * (PROCESSING/SUCCESS), it cannot be auto-recovered — we flag it for manual clawback via an audit
   * event. Partial refunds on a not-yet-paid payout are also flagged (proportional recompute is an
   * accounting decision).
   */
  @Transactional
  public void reverseOwnerPayoutForRefund(PaymentIntent triggeringIntent, boolean fullRefund) {
    if (triggeringIntent == null) {
      return;
    }
    Payout payout = payoutRepository.findByTriggeringPaymentIntent(triggeringIntent).orElse(null);
    if (payout == null) {
      return;
    }
    String status = payout.getStatus();
    boolean notYetPaid = "PENDING".equals(status) || "PENDING_METHOD".equals(status);

    if (notYetPaid && fullRefund) {
      String old = payout.getStatus();
      payout.setStatus("REVERSED");
      payout.setFailureReason("Reversed: triggering charge was refunded before payout");
      payout.setProcessedAt(LocalDateTime.now());
      payoutRepository.save(payout);
      eventLogger.log(
          "PAYOUT",
          payout.getId(),
          "REVERSED",
          old,
          "REVERSED",
          null,
          null,
          payout.getIdempotencyKey(),
          java.util.Map.of("reason", "charge_refunded"));
      log.info("Payout {} reversed (charge refunded before payout)", payout.getId());
    } else {
      // Either already dispatched/paid, or a partial refund on a pending payout:
      // cannot safely auto-adjust — record for manual clawback / review.
      eventLogger.log(
          "PAYOUT",
          payout.getId(),
          "CLAWBACK_REQUIRED",
          status,
          status,
          null,
          null,
          payout.getIdempotencyKey(),
          java.util.Map.of("fullRefund", String.valueOf(fullRefund)));
      log.warn(
          "Payout {} (status {}) needs manual clawback — its charge was refunded (full={})",
          payout.getId(),
          status,
          fullRefund);
    }
  }

  @Transactional(readOnly = true)
  public List<Payout> listMine(User user) {
    return payoutRepository.findByUserOrderByCreatedAtDesc(user);
  }

  @Transactional(readOnly = true)
  public Payout getMine(User user, Long id) {
    Payout p =
        payoutRepository
            .findById(id)
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
        .orElseThrow(
            () ->
                new InvalidRequestException(
                    "Card token does not belong to you or is not an active saved card"));

    // Idempotent: re-registering an already-connected card returns the existing method
    // (also avoids tripping the unique (user, provider_card_token) constraint).
    PayoutMethod already =
        payoutMethodRepository
            .findByUserAndProviderCardTokenAndStatus(user, providerCardToken, "ACTIVE")
            .orElse(null);
    if (already != null) {
      return already;
    }

    boolean firstMethod =
        payoutMethodRepository.findByUserAndIsDefaultTrueAndStatus(user, "ACTIVE").isEmpty();
    PayoutMethod method =
        PayoutMethod.builder()
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
    return payoutMethodRepository.findByUserAndStatusOrderByIsDefaultDescCreatedAtDesc(
        user, "ACTIVE");
  }

  @Transactional
  public void revokeMethod(User user, Long id) {
    PayoutMethod method =
        payoutMethodRepository
            .findById(id)
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
