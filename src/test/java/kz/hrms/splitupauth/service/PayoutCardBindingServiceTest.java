package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import kz.hrms.splitupauth.entity.PayoutCardBinding;
import kz.hrms.splitupauth.entity.PayoutMethod;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.payment.gateway.GatewayCardBindingRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayCardBindingResponse;
import kz.hrms.splitupauth.payment.gateway.GatewayRefundRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayWebhookEvent;
import kz.hrms.splitupauth.payment.gateway.PaymentGateway;
import kz.hrms.splitupauth.payment.gateway.PaymentGatewayRegistry;
import kz.hrms.splitupauth.repository.PayoutCardBindingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PayoutCardBindingServiceTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Almaty");
  private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

  @Mock private PayoutCardBindingRepository bindingRepository;
  @Mock private PaymentGatewayRegistry gatewayRegistry;
  @Mock private PayoutService payoutService;
  @Mock private PaymentGateway gateway;

  private PayoutCardBindingService service;
  private final User user = User.builder().id(42L).build();

  @BeforeEach
  void setUp() {
    service =
        new PayoutCardBindingService(
            bindingRepository, gatewayRegistry, payoutService, Clock.fixed(NOW, ZONE));
    ReflectionTestUtils.setField(service, "frontendUrl", "https://app.test");
  }

  private PayoutCardBinding pending(String providerId, LocalDateTime createdAt) {
    return PayoutCardBinding.builder()
        .id(17L)
        .user(user)
        .providerName("freedompay")
        .externalPaymentId(providerId)
        .amount(new BigDecimal("0.00"))
        .currency("KZT")
        .status("PENDING")
        .idempotencyKey("cardbind-42-17")
        .createdAt(createdAt)
        .build();
  }

  private static GatewayWebhookEvent callback(String status, String token, String userId) {
    return GatewayWebhookEvent.builder()
        .kind("PAYOUT_CARD")
        .resultStatus(status)
        .externalPaymentId("provider-binding-17")
        .cardToken(token)
        .cardPanMask("411111******1111")
        .userId(userId)
        .build();
  }

  @Test
  void initUsesZeroAmountPayoutTokenizationAndIgnoresClientReturnUrl() {
    when(gatewayRegistry.defaultGateway()).thenReturn(gateway);
    when(gateway.providerName()).thenReturn("freedompay");
    when(bindingRepository.save(any(PayoutCardBinding.class)))
        .thenAnswer(
            invocation -> {
              PayoutCardBinding binding = invocation.getArgument(0);
              if (binding.getId() == null) binding.setId(17L);
              return binding;
            });
    when(gateway.initCardBinding(any()))
        .thenReturn(
            GatewayCardBindingResponse.builder()
                .success(true)
                .externalBindingId("provider-binding-17")
                .redirectUrl("https://pay.test/cardstoragepayout/view")
                .requiresRedirect(true)
                .build());

    var response = service.initBinding(user, "https://attacker.test/redirect");

    ArgumentCaptor<PayoutCardBinding> binding = ArgumentCaptor.forClass(PayoutCardBinding.class);
    verify(bindingRepository, org.mockito.Mockito.atLeastOnce()).save(binding.capture());
    assertEquals(new BigDecimal("0.00"), binding.getAllValues().get(0).getAmount());
    assertEquals("provider-binding-17", binding.getValue().getExternalPaymentId());
    ArgumentCaptor<GatewayCardBindingRequest> request =
        ArgumentCaptor.forClass(GatewayCardBindingRequest.class);
    verify(gateway).initCardBinding(request.capture());
    assertEquals("42", request.getValue().getUserId());
    assertEquals(
        "https://app.test/payment/card-connected?binding=17", request.getValue().getBackUrl());
    assertEquals("https://pay.test/cardstoragepayout/view", response.getPaymentUrl());
    assertEquals("PENDING", response.getStatus());
    verify(gateway, never()).refund(any(GatewayRefundRequest.class));
    verifyNoInteractions(payoutService);
  }

  @Test
  void initFailureNeverLeaksProviderDetails() {
    when(gatewayRegistry.defaultGateway()).thenReturn(gateway);
    when(gateway.providerName()).thenReturn("freedompay");
    when(bindingRepository.save(any(PayoutCardBinding.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(gateway.initCardBinding(any()))
        .thenReturn(
            GatewayCardBindingResponse.builder()
                .success(false)
                .failureCode("1100")
                .failureMessage("Некорректная подпись запроса")
                .build());

    var response = service.initBinding(user, null);

    assertEquals("FAILED", response.getStatus());
    assertFalse(response.getFailureMessage().contains("подпись"));
  }

  @Test
  void signedPayoutCardCallbackRegistersVerifiedPayoutMethod() {
    PayoutCardBinding binding = pending("provider-binding-17", LocalDateTime.now());
    PayoutMethod method =
        PayoutMethod.builder().id(9L).user(user).providerCardToken("payout-token").build();
    when(bindingRepository.findByExternalPaymentId("provider-binding-17"))
        .thenReturn(Optional.of(binding));
    when(payoutService.registerVerifiedPayoutMethod(user, "payout-token", "411111******1111"))
        .thenReturn(method);

    service.applyPayoutCardWebhook(callback("SUCCESS", "payout-token", "42"));

    assertEquals("SUCCESS", binding.getStatus());
    assertEquals(method, binding.getPayoutMethod());
    assertEquals("411111******1111", binding.getPanMask());
  }

  @Test
  void duplicatePayoutCardCallbackIsANoOp() {
    PayoutCardBinding binding = pending("provider-binding-17", LocalDateTime.now());
    binding.setStatus("SUCCESS");
    when(bindingRepository.findByExternalPaymentId("provider-binding-17"))
        .thenReturn(Optional.of(binding));

    service.applyPayoutCardWebhook(callback("SUCCESS", "another-token", "42"));

    verifyNoInteractions(payoutService);
  }

  @Test
  void callbackForAnotherUserIsRejectedAndRegistersNothing() {
    PayoutCardBinding binding = pending("provider-binding-17", LocalDateTime.now());
    when(bindingRepository.findByExternalPaymentId("provider-binding-17"))
        .thenReturn(Optional.of(binding));

    FreedomWebhookProcessingException ex =
        assertThrows(
            FreedomWebhookProcessingException.class,
            () -> service.applyPayoutCardWebhook(callback("SUCCESS", "payout-token", "777")));

    assertEquals("BINDING_USER_MISMATCH", ex.getErrorCode());
    assertFalse(ex.isRetryable());
    assertEquals("PENDING", binding.getStatus());
    verifyNoInteractions(payoutService);
  }

  @Test
  void failedOrTokenlessCallbackNeverCreatesAPayoutMethod() {
    PayoutCardBinding binding = pending("provider-binding-17", LocalDateTime.now());
    when(bindingRepository.findByExternalPaymentId("provider-binding-17"))
        .thenReturn(Optional.of(binding));

    service.applyPayoutCardWebhook(callback("SUCCESS", null, "42"));

    assertEquals("FAILED", binding.getStatus());
    verifyNoInteractions(payoutService);
  }

  @Test
  void unknownBindingIsRetryableSoTheInboxCanRetryLater() {
    when(bindingRepository.findByExternalPaymentId("provider-binding-17"))
        .thenReturn(Optional.empty());

    FreedomWebhookProcessingException ex =
        assertThrows(
            FreedomWebhookProcessingException.class,
            () -> service.applyPayoutCardWebhook(callback("SUCCESS", "t", "42")));
    assertEquals(true, ex.isRetryable());
  }

  @Test
  void purchaseCardStorageCallbackClosesBindingAsRebindRequired() {
    PayoutCardBinding binding = pending(null, LocalDateTime.now());
    when(bindingRepository.findById(17L)).thenReturn(Optional.of(binding));

    service.rejectLegacyPurchaseCardCallback(17L);

    assertEquals("FAILED", binding.getStatus());
    assertEquals(true, binding.getFailureMessage().startsWith("REBIND_REQUIRED"));
    verifyNoInteractions(payoutService);
  }

  @Test
  void reachingTheReturnPageNeverConnectsACardOrQueriesTheProvider() {
    PayoutCardBinding binding =
        pending("provider-binding-17", LocalDateTime.ofInstant(NOW, ZONE).minusMinutes(5));
    when(bindingRepository.findByIdAndUser(17L, user)).thenReturn(Optional.of(binding));

    var response = service.confirmBinding(user, 17L);

    assertEquals("PENDING", response.getStatus());
    assertNull(binding.getPayoutMethod());
    verifyNoInteractions(gatewayRegistry, payoutService);
  }

  @Test
  void staleUnconfirmedBindingIsClosedAsFailed() {
    PayoutCardBinding binding =
        pending("provider-binding-17", LocalDateTime.ofInstant(NOW, ZONE).minusMinutes(45));
    when(bindingRepository.findByIdAndUser(17L, user)).thenReturn(Optional.of(binding));

    var response = service.confirmBinding(user, 17L);

    assertEquals("FAILED", response.getStatus());
    verifyNoInteractions(payoutService);
  }
}
