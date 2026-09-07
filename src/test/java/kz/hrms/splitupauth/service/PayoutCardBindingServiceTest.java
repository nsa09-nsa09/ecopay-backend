package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import kz.hrms.splitupauth.entity.PayoutCardBinding;
import kz.hrms.splitupauth.entity.PayoutMethod;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.payment.gateway.GatewayCardBindingRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayCardBindingResponse;
import kz.hrms.splitupauth.payment.gateway.GatewayRefundRequest;
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

  @Mock private PayoutCardBindingRepository bindingRepository;
  @Mock private PaymentGatewayRegistry gatewayRegistry;
  @Mock private PayoutService payoutService;
  @Mock private PaymentGateway gateway;

  private PayoutCardBindingService service;

  @BeforeEach
  void setUp() {
    service = new PayoutCardBindingService(bindingRepository, gatewayRegistry, payoutService);
    ReflectionTestUtils.setField(service, "frontendUrl", "https://app.test");
  }

  @Test
  void initUsesZeroAmountCardBindingInsteadOfVerificationCharge() {
    User user = User.builder().id(42L).build();
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
                .redirectUrl("https://pay.test/add2")
                .requiresRedirect(true)
                .build());

    var response = service.initBinding(user, "https://attacker.test/redirect");

    ArgumentCaptor<PayoutCardBinding> binding =
        ArgumentCaptor.forClass(PayoutCardBinding.class);
    verify(bindingRepository, org.mockito.Mockito.atLeastOnce()).save(binding.capture());
    assertEquals(new BigDecimal("0.00"), binding.getAllValues().get(0).getAmount());
    ArgumentCaptor<GatewayCardBindingRequest> request =
        ArgumentCaptor.forClass(GatewayCardBindingRequest.class);
    verify(gateway).initCardBinding(request.capture());
    assertEquals("42", request.getValue().getUserId());
    assertEquals(
        "https://app.test/payment/card-connected?binding=17", request.getValue().getBackUrl());
    assertEquals("https://pay.test/add2", response.getPaymentUrl());
    assertEquals("PENDING", response.getStatus());
    verify(gateway, never()).refund(any(GatewayRefundRequest.class));
  }

  @Test
  void signedCallbackRegistersTokenWithoutRefund() {
    User user = User.builder().id(42L).build();
    PayoutCardBinding binding =
        PayoutCardBinding.builder()
            .id(17L)
            .user(user)
            .providerName("freedompay")
            .amount(new BigDecimal("0.00"))
            .currency("KZT")
            .status("PENDING")
            .idempotencyKey("cardbind-42-17")
            .build();
    PayoutMethod method =
        PayoutMethod.builder().id(9L).user(user).providerCardToken("payout-token").build();
    when(bindingRepository.findById(17L)).thenReturn(Optional.of(binding));
    when(payoutService.registerVerifiedPayoutMethod(
            user, "payout-token", "411111******1111"))
        .thenReturn(method);

    service.applyBindingWebhook(17L, true, "payout-token", "411111******1111");

    assertEquals("SUCCESS", binding.getStatus());
    assertEquals(method, binding.getPayoutMethod());
    assertEquals("411111******1111", binding.getPanMask());
    verify(bindingRepository).save(binding);
    verify(gateway, never()).refund(any(GatewayRefundRequest.class));
  }

  @Test
  void pendingConfirmationDoesNotAttachAnUnrelatedSavedCard() {
    User user = User.builder().id(42L).build();
    PayoutCardBinding binding =
        PayoutCardBinding.builder()
            .id(17L)
            .user(user)
            .status("PENDING")
            .amount(new BigDecimal("0.00"))
            .currency("KZT")
            .idempotencyKey("cardbind-42-17")
            .build();
    when(bindingRepository.findByIdAndUser(17L, user)).thenReturn(Optional.of(binding));

    var response = service.confirmBinding(user, 17L);

    assertEquals("PENDING", response.getStatus());
    assertNull(binding.getPayoutMethod());
    verify(gatewayRegistry, never()).defaultGateway();
  }
}
