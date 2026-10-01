package kz.hrms.splitupauth.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import kz.hrms.splitupauth.dto.PayoutCardBindingConfirmResponse;
import kz.hrms.splitupauth.dto.PayoutCardBindingResponse;
import kz.hrms.splitupauth.dto.PayoutMethodDto;
import kz.hrms.splitupauth.entity.PayoutCardBinding;
import kz.hrms.splitupauth.entity.PayoutMethod;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.payment.gateway.GatewayCardBindingRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayCardBindingResponse;
import kz.hrms.splitupauth.payment.gateway.GatewayStatusResponse;
import kz.hrms.splitupauth.payment.gateway.PaymentGateway;
import kz.hrms.splitupauth.payment.gateway.PaymentGatewayRegistry;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway;
import kz.hrms.splitupauth.repository.PayoutCardBindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner payout-card connection via the provider's hosted page.
 *
 * <p>FreedomPay's universal {@code cardstorage/add2} hosted flow tokenizes the card without a
 * verification charge. Its signed callback provides the payout-compatible card token, which is then
 * registered as the owner's payout method. The owner never sees or types a token.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PayoutCardBindingService {

  private final PayoutCardBindingRepository bindingRepository;
  private final PaymentGatewayRegistry gatewayRegistry;
  private final PayoutService payoutService;

  @Value("${app.frontend-url}")
  private String frontendUrl;

  /**
   * Starts a zero-amount binding and returns the hosted page where the owner enters their card. The
   * client-supplied return URL is ignored; redirects always use the configured frontend.
   */
  @Transactional
  public PayoutCardBindingResponse initBinding(User user, String ignoredReturnUrl) {
    PaymentGateway gateway = gatewayRegistry.defaultGateway();

    PayoutCardBinding binding =
        bindingRepository.save(
            PayoutCardBinding.builder()
                .user(user)
                .providerName(gateway.providerName())
                .amount(BigDecimal.ZERO.setScale(2))
                .currency("KZT")
                .status("PENDING")
                .idempotencyKey("cardbind-" + user.getId() + "-" + UUID.randomUUID())
                .build());

    String trustedReturnUrl = trustedReturnUrl();
    String backUrl = appendQuery(trustedReturnUrl, "binding=" + binding.getId());

    GatewayCardBindingResponse resp;
    try {
      resp =
          gateway.initCardBinding(
              GatewayCardBindingRequest.builder()
                  .bindingId(binding.getId())
                  .idempotencyKey(binding.getIdempotencyKey())
                  .userId(String.valueOf(user.getId()))
                  .backUrl(backUrl)
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

    binding.setExternalPaymentId(resp.getExternalBindingId());
    bindingRepository.save(binding);

    if (resp.getCardToken() != null && !resp.getCardToken().isBlank()) {
      completeBinding(binding, user, resp.getCardToken(), resp.getCardPanMask());
      return PayoutCardBindingResponse.builder()
          .bindingId(binding.getId())
          .requiresRedirect(false)
          .status("SUCCESS")
          .build();
    }

    return PayoutCardBindingResponse.builder()
        .bindingId(binding.getId())
        .paymentUrl(resp.getRedirectUrl())
        .requiresRedirect(resp.isRequiresRedirect())
        .status("PENDING")
        .build();
  }

  /**
   * Reads a binding after the owner returns. Checks if webhook already completed it, or actively
   * reconciles with the provider if still pending. Idempotent.
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

    // Active reconciliation: in local dev or before webhook delivery, query provider directly
    if (reconcileBindingWithProvider(binding, user)) {
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

    return PayoutCardBindingConfirmResponse.builder()
        .status("PENDING")
        .message("Card tokenization is still being confirmed by the provider.")
        .build();
  }

  /**
   * Reconciles all pending card bindings for the specified user with the provider. Useful when
   * returning to room creation or method list without waiting for a webhook.
   */
  @Transactional
  public void reconcilePendingBindingsForUser(User user) {
    if (user == null) return;
    List<PayoutCardBinding> pending =
        bindingRepository.findByUserAndStatusOrderByCreatedAtDesc(user, "PENDING");
    for (PayoutCardBinding binding : pending) {
      if (reconcileBindingWithProvider(binding, user)) {
        break;
      }
    }
  }

  /**
   * Directly queries the provider for the outcome of this specific binding transaction. Returns
   * true if the binding reached SUCCESS.
   */
  public boolean reconcileBindingWithProvider(PayoutCardBinding binding, User user) {
    if (binding == null || !"PENDING".equals(binding.getStatus())) {
      return "SUCCESS".equals(binding != null ? binding.getStatus() : null);
    }
    if (binding.getExternalPaymentId() == null || binding.getExternalPaymentId().isBlank()) {
      return false;
    }
    try {
      PaymentGateway gateway =
          binding.getProviderName() != null
              ? gatewayRegistry.resolve(binding.getProviderName())
              : gatewayRegistry.defaultGateway();
      if (gateway == null) {
        return false;
      }
      GatewayStatusResponse statusResp = gateway.getStatus(binding.getExternalPaymentId());
      if (statusResp == null) {
        return false;
      }

      String token = statusResp.getCardToken();
      String mask = statusResp.getCardPanMask();

      // If provider marked transaction as success but omitted card token in get_status,
      // look up the card stored for this user via cardstorage/list
      if ("SUCCESS".equals(statusResp.getStatus())
          && (token == null || token.isBlank())
          && gateway instanceof FreedomPayGateway freedomGateway) {
        GatewayStatusResponse savedCard =
            freedomGateway.fetchSavedCardForUser(String.valueOf(user.getId()));
        if (savedCard != null
            && savedCard.getCardToken() != null
            && !savedCard.getCardToken().isBlank()) {
          token = savedCard.getCardToken();
          if (mask == null || mask.isBlank()) {
            mask = savedCard.getCardPanMask();
          }
        }
      }

      if ("SUCCESS".equals(statusResp.getStatus()) && token != null && !token.isBlank()) {
        completeBinding(binding, user, token, mask);
        log.info("Binding {} actively reconciled with provider: status=SUCCESS", binding.getId());
        return true;
      } else if ("FAILED".equals(statusResp.getStatus())) {
        binding.setStatus("FAILED");
        binding.setFailureMessage(statusResp.getFailureMessage());
        bindingRepository.save(binding);
        log.info("Binding {} actively reconciled with provider: status=FAILED", binding.getId());
        return false;
      }
    } catch (Exception ex) {
      log.warn("Active reconciliation for binding {} failed: {}", binding.getId(), ex.getMessage());
    }
    return false;
  }

  /**
   * Finalize a binding from the Freedom Pay callback carrying {@code pg_card_token}. Routed here
   * when the order id is a {@code cardbind-...} marker. Idempotent.
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

  /** Save the token, register the payout method, and mark the zero-amount binding successful. */
  private PayoutMethod completeBinding(
      PayoutCardBinding binding, User user, String token, String panMask) {
    PayoutMethod method = payoutService.registerVerifiedPayoutMethod(user, token, panMask);

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
      throw new IllegalStateException(
          "app.frontend-url must be configured for payout card binding");
    }
    return origin + "/payment/card-connected";
  }
}
