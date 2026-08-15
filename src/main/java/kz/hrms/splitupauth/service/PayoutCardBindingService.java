package kz.hrms.splitupauth.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import kz.hrms.splitupauth.dto.PayoutCardBindingConfirmResponse;
import kz.hrms.splitupauth.dto.PayoutCardBindingResponse;
import kz.hrms.splitupauth.dto.PayoutMethodDto;
import kz.hrms.splitupauth.entity.PayoutCardBinding;
import kz.hrms.splitupauth.entity.PayoutMethod;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.payment.gateway.GatewayChargeRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayChargeResponse;
import kz.hrms.splitupauth.payment.gateway.GatewayRefundRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayStatusResponse;
import kz.hrms.splitupauth.payment.gateway.PaymentGateway;
import kz.hrms.splitupauth.payment.gateway.PaymentGatewayRegistry;
import kz.hrms.splitupauth.repository.PayoutCardBindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner payout-card connection via the provider's hosted page.
 *
 * <p>FreedomPay only hands back a reusable card token after a card has gone through its hosted
 * page, so we run a small verification charge (card-save enabled) to tokenize the card, then
 * auto-refund it. The resulting token is saved and registered as the owner's payout method — the
 * owner never sees or types a token. This flow is deliberately isolated from the room-payment
 * {@code PaymentIntent} path (which is bound to a room member).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PayoutCardBindingService {

  private final PayoutCardBindingRepository bindingRepository;
  private final PaymentGatewayRegistry gatewayRegistry;
  private final PayoutService payoutService;

  /** Verification charge amount (KZT). Auto-refunded once the card is tokenized. */
  @Value("${app.payout.card-binding-amount:100}")
  private BigDecimal cardBindingAmount;

  @Value("${app.frontend-url}")
  private String frontendUrl;

  /**
   * Starts a binding: creates a verification charge on the hosted page and returns the URL the
   * owner must visit to enter their card. {@code returnUrl} is the frontend page the provider
   * should redirect back to; the binding id and status are appended to it.
   */
  @Transactional
  public PayoutCardBindingResponse initBinding(User user, String returnUrl) {
    PaymentGateway gateway = gatewayRegistry.defaultGateway();

    PayoutCardBinding binding =
        bindingRepository.save(
            PayoutCardBinding.builder()
                .user(user)
                .providerName(gateway.providerName())
                .amount(cardBindingAmount)
                .currency("KZT")
                .status("PENDING")
                .idempotencyKey("cardbind-" + user.getId() + "-" + UUID.randomUUID())
                .build());

    String trustedReturnUrl = trustedReturnUrl();
    String successUrl =
        appendQuery(trustedReturnUrl, "binding=" + binding.getId() + "&status=success");
    String failureUrl =
        appendQuery(trustedReturnUrl, "binding=" + binding.getId() + "&status=failure");

    GatewayChargeResponse resp;
    try {
      resp =
          gateway.initCharge(
              GatewayChargeRequest.builder()
                  // Non-numeric order id keeps this binding's webhook from being mistaken
                  // for a real PaymentIntent (which uses a bare numeric order id).
                  .orderIdOverride("cardbind-" + binding.getId())
                  .idempotencyKey(binding.getIdempotencyKey())
                  .amount(cardBindingAmount)
                  .currency("KZT")
                  .description("EcoPay payout card verification")
                  .userEmail(user.getEmail())
                  .userPhone(user.getPhone())
                  .userId(String.valueOf(user.getId()))
                  .saveCardRequested(true)
                  .successUrl(successUrl)
                  .failureUrl(failureUrl)
                  .build());
    } catch (Exception ex) {
      log.error("Payout card binding {} init failed: {}", binding.getId(), ex.getMessage());
      binding.setStatus("FAILED");
      binding.setFailureMessage("Gateway initiation failed: " + ex.getMessage());
      bindingRepository.save(binding);
      return PayoutCardBindingResponse.builder()
          .bindingId(binding.getId())
          .status("FAILED")
          .failureMessage(binding.getFailureMessage())
          .build();
    }

    if (!resp.isSuccess()) {
      binding.setStatus("FAILED");
      binding.setFailureMessage(resp.getFailureMessage());
      bindingRepository.save(binding);
      return PayoutCardBindingResponse.builder()
          .bindingId(binding.getId())
          .status("FAILED")
          .failureMessage(resp.getFailureMessage())
          .build();
    }

    binding.setExternalPaymentId(resp.getExternalPaymentId());
    bindingRepository.save(binding);

    return PayoutCardBindingResponse.builder()
        .bindingId(binding.getId())
        .paymentUrl(resp.getPaymentUrl())
        .requiresRedirect(resp.isRequiresRedirect())
        .status("PENDING")
        .build();
  }

  /**
   * Confirms a binding after the owner returns from the hosted page: queries the provider for the
   * card token, saves it, registers the payout method, and refunds the verification charge.
   * Idempotent — a binding that already succeeded just returns its payout method.
   */
  @Transactional
  public PayoutCardBindingConfirmResponse confirmBinding(User user, Long bindingId) {
    PayoutCardBinding binding =
        bindingRepository
            .findByIdAndUser(bindingId, user)
            .orElseThrow(() -> new ResourceNotFoundException("Card binding not found"));

    if ("SUCCESS".equals(binding.getStatus())) {
      return PayoutCardBindingConfirmResponse.builder()
          .status("SUCCESS")
          .method(
              binding.getPayoutMethod() == null
                  ? null
                  : PayoutMethodDto.from(binding.getPayoutMethod()))
          .build();
    }
    if ("FAILED".equals(binding.getStatus())) {
      return PayoutCardBindingConfirmResponse.builder()
          .status("FAILED")
          .message(binding.getFailureMessage())
          .build();
    }
    if (binding.getExternalPaymentId() == null) {
      return PayoutCardBindingConfirmResponse.builder().status("PENDING").build();
    }

    PaymentGateway gateway = gatewayRegistry.defaultGateway();
    GatewayStatusResponse status = gateway.getStatus(binding.getExternalPaymentId());

    if ("SUCCESS".equals(status.getStatus())) {
      String token = status.getCardToken();
      String panMask = status.getCardPanMask();

      // get_status does not return the saved-card token; look it up via the cardstorage
      // list (keyed by pg_user_id). This avoids needing the result webhook on localhost.
      if ((token == null || token.isBlank())
          && gateway instanceof kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway fp) {
        GatewayStatusResponse saved = fp.fetchSavedCardForUser(String.valueOf(user.getId()));
        if (saved != null && saved.getCardToken() != null && !saved.getCardToken().isBlank()) {
          token = saved.getCardToken();
          if (panMask == null || panMask.isBlank()) panMask = saved.getCardPanMask();
        }
      }

      if (token == null || token.isBlank()) {
        // Charged, but the token isn't available yet (e.g. webhook-only delivery). Stay
        // PENDING so the owner can retry — do NOT fail; the charge succeeded.
        return PayoutCardBindingConfirmResponse.builder()
            .status("PENDING")
            .message("Card charged — still finalizing. Please re-check in a moment.")
            .build();
      }

      PayoutMethod method = completeBinding(binding, user, token, panMask);
      return PayoutCardBindingConfirmResponse.builder()
          .status("SUCCESS")
          .method(PayoutMethodDto.from(method))
          .build();
    }

    if ("FAILED".equals(status.getStatus())) {
      binding.setStatus("FAILED");
      binding.setFailureMessage(status.getFailureMessage());
      bindingRepository.save(binding);
      return PayoutCardBindingConfirmResponse.builder()
          .status("FAILED")
          .message(status.getFailureMessage())
          .build();
    }

    // Still pending at the provider — the owner may not have finished, or it's settling.
    return PayoutCardBindingConfirmResponse.builder().status("PENDING").build();
  }

  /**
   * Finalize a binding from the Freedom Pay result webhook. The webhook carries the saved-card
   * token (pg_recurring_profile_id) for card-save flows, so this is the reliable completion path
   * when the cardstorage lookup isn't available. Routed here by FreedomPayWebhookController when
   * the order id is a "cardbind-..." marker. Idempotent.
   */
  @Transactional
  public void applyBindingWebhook(Long bindingId, boolean success, String token, String panMask) {
    if (bindingId == null) return;
    PayoutCardBinding binding = bindingRepository.findById(bindingId).orElse(null);
    if (binding == null) {
      throw new FreedomWebhookProcessingException(
          "BINDING_NOT_FOUND", "Webhook references unknown card binding " + bindingId, true);
    }
    if ("SUCCESS".equals(binding.getStatus()) || "FAILED".equals(binding.getStatus())) {
      return; // terminal — idempotent no-op
    }
    if (success && token != null && !token.isBlank()) {
      completeBinding(binding, binding.getUser(), token, panMask);
      log.info("Binding {} completed via webhook", bindingId);
    } else {
      binding.setStatus("FAILED");
      binding.setFailureMessage("Provider webhook reported failure or returned no card token");
      bindingRepository.save(binding);
    }
  }

  /** Save the token, register the payout method, refund the verification charge, mark SUCCESS. */
  private PayoutMethod completeBinding(
      PayoutCardBinding binding, User user, String token, String panMask) {
    PayoutMethod method = payoutService.registerVerifiedPayoutMethod(user, token, panMask);

    // Return the verification charge. Best-effort: a failed refund must not block the binding
    // (the amount is small and visible for manual reconciliation).
    try {
      gatewayRegistry
          .defaultGateway()
          .refund(
              GatewayRefundRequest.builder()
                  .externalPaymentId(binding.getExternalPaymentId())
                  .amount(binding.getAmount())
                  .currency(binding.getCurrency())
                  .reason("Payout card verification refund")
                  .idempotencyKey("cardbind-refund-" + binding.getId())
                  .build());
    } catch (Exception ex) {
      log.warn(
          "Card binding {} verification refund failed (manual reconcile needed): {}",
          binding.getId(),
          ex.getMessage());
    }

    binding.setStatus("SUCCESS");
    binding.setPanMask(panMask);
    binding.setPayoutMethod(method);
    binding.setCompletedAt(LocalDateTime.now());
    bindingRepository.save(binding);
    return method;
  }

  private static String appendQuery(String url, String extra) {
    if (url == null || url.isBlank()) {
      return url;
    }
    return url + (url.contains("?") ? "&" : "?") + extra;
  }

  private String trustedReturnUrl() {
    String origin = frontendUrl == null ? "" : frontendUrl.trim();
    while (origin.endsWith("/")) {
      origin = origin.substring(0, origin.length() - 1);
    }
    if (origin.isBlank()) {
      throw new IllegalStateException("app.frontend-url must be configured for payout card binding");
    }
    return origin + "/payment/card-connected";
  }
}
