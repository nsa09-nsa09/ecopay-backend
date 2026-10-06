package kz.hrms.splitupauth.service;

import java.math.BigDecimal;
import java.time.Clock;
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
import kz.hrms.splitupauth.payment.gateway.GatewayWebhookEvent;
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
 * <p>FreedomPay has a dedicated PAYOUT card tokenization ({@code /cardstoragepayout/add}); cards
 * saved there "can only be used for payouts", i.e. exactly with {@code /api/reg2reg}. A payout
 * method is created ONLY from the token delivered by that flow's signed callback. Purchase card
 * storage tokens ({@code cardstorage/add2}) and recurring profiles are never assumed to be payout
 * compatible, and the browser return to the frontend never connects a card by itself.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PayoutCardBindingService {

  /** A tokenization page left unfinished this long is closed as failed (owner can retry). */
  static final long BINDING_TIMEOUT_MINUTES = 30;

  private final PayoutCardBindingRepository bindingRepository;
  private final PaymentGatewayRegistry gatewayRegistry;
  private final PayoutService payoutService;
  private final Clock clock;

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
      // No money is involved: an ambiguous tokenization start is simply closed, the owner retries.
      log.error(
          "Payout card binding {} init failed: {}", binding.getId(), ex.getClass().getSimpleName());
      return fail(binding, "Card connection could not be started. Please try again.");
    }

    if (!resp.isSuccess()) {
      log.warn(
          "Payout card binding {} rejected by provider: code={}",
          binding.getId(),
          resp.getFailureCode());
      return fail(binding, "Card connection could not be started. Please try again.");
    }

    binding.setExternalPaymentId(resp.getExternalBindingId());
    bindingRepository.save(binding);

    if (resp.getCardToken() != null && !resp.getCardToken().isBlank()) {
      // Synchronous gateways (the in-memory mock) tokenize without a hosted page.
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
   * Reads a binding after the owner returns from the hosted page. Reaching the return URL proves
   * nothing: the binding stays PENDING until the signed provider callback arrives, and is closed as
   * FAILED once it is older than {@link #BINDING_TIMEOUT_MINUTES}. Idempotent.
   */
  @Transactional
  public PayoutCardBindingConfirmResponse confirmBinding(User user, Long bindingId) {
    PayoutCardBinding binding =
        bindingRepository
            .findByIdAndUser(bindingId, user)
            .orElseThrow(() -> new ResourceNotFoundException("Card binding not found"));

    expireIfStale(binding);
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
    return PayoutCardBindingConfirmResponse.builder()
        .status("PENDING")
        .message("Card connection is still being confirmed by the payment provider.")
        .build();
  }

  /** Closes this user's stale pending bindings. Never calls the provider. */
  @Transactional
  public void reconcilePendingBindingsForUser(User user) {
    if (user == null) return;
    List<PayoutCardBinding> pending =
        bindingRepository.findByUserAndStatusOrderByCreatedAtDesc(user, "PENDING");
    pending.forEach(this::expireIfStale);
  }

  /**
   * Applies a verified {@code cardstoragepayout} callback. The binding is matched by the provider's
   * {@code pg_payment_id} returned at init (the endpoint has no order id) and, when the callback
   * carries {@code pg_user_id}, it must be the binding owner's id. Idempotent.
   */
  @Transactional
  public void applyPayoutCardWebhook(GatewayWebhookEvent event) {
    String providerId = event.getExternalPaymentId();
    if (providerId == null || providerId.isBlank()) {
      throw new FreedomWebhookProcessingException(
          "MISSING_PROVIDER_ID", "Payout-card callback has no pg_payment_id", false);
    }
    PayoutCardBinding binding = bindingRepository.findByExternalPaymentId(providerId).orElse(null);
    if (binding == null) {
      throw new FreedomWebhookProcessingException(
          "BINDING_NOT_FOUND", "Payout-card callback references an unknown binding", true);
    }
    if (event.getUserId() != null
        && !event.getUserId().isBlank()
        && !event.getUserId().trim().equals(String.valueOf(binding.getUser().getId()))) {
      throw new FreedomWebhookProcessingException(
          "BINDING_USER_MISMATCH", "Payout-card callback user does not match the binding", false);
    }
    if ("SUCCESS".equals(binding.getStatus()) || "FAILED".equals(binding.getStatus())) {
      return; // terminal — duplicate callback is a no-op
    }
    if ("SUCCESS".equals(event.getResultStatus())
        && event.getCardToken() != null
        && !event.getCardToken().isBlank()) {
      completeBinding(binding, binding.getUser(), event.getCardToken(), event.getCardPanMask());
      log.info("Payout card binding {} completed via signed callback", binding.getId());
    } else {
      binding.setStatus("FAILED");
      binding.setFailureMessage("The card could not be connected. Please try again.");
      bindingRepository.save(binding);
    }
  }

  /**
   * A purchase card-storage ({@code add2}) callback for a binding started before the payout-card
   * flow existed. Its token is not a proven payout destination, so the binding is closed and the
   * owner is asked to reconnect. Idempotent.
   */
  @Transactional
  public void rejectLegacyPurchaseCardCallback(Long bindingId) {
    if (bindingId == null) return;
    PayoutCardBinding binding = bindingRepository.findById(bindingId).orElse(null);
    if (binding == null || !"PENDING".equals(binding.getStatus())) {
      return;
    }
    binding.setStatus("FAILED");
    binding.setFailureMessage("REBIND_REQUIRED: please connect the payout card again.");
    bindingRepository.save(binding);
  }

  private void expireIfStale(PayoutCardBinding binding) {
    if (!"PENDING".equals(binding.getStatus()) || binding.getCreatedAt() == null) {
      return;
    }
    if (binding
        .getCreatedAt()
        .plusMinutes(BINDING_TIMEOUT_MINUTES)
        .isBefore(LocalDateTime.now(clock))) {
      binding.setStatus("FAILED");
      binding.setFailureMessage("The card connection was not completed in time. Please try again.");
      bindingRepository.save(binding);
    }
  }

  private PayoutCardBindingResponse fail(PayoutCardBinding binding, String userMessage) {
    binding.setStatus("FAILED");
    binding.setFailureMessage(userMessage);
    bindingRepository.save(binding);
    return PayoutCardBindingResponse.builder()
        .bindingId(binding.getId())
        .status("FAILED")
        .failureMessage(userMessage)
        .build();
  }

  /** Save the token, register the payout method, and mark the zero-amount binding successful. */
  private PayoutMethod completeBinding(
      PayoutCardBinding binding, User user, String token, String panMask) {
    PayoutMethod method = payoutService.registerVerifiedPayoutMethod(user, token, panMask);

    binding.setStatus("SUCCESS");
    binding.setPanMask(panMask);
    binding.setPayoutMethod(method);
    binding.setCompletedAt(LocalDateTime.now(clock));
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
