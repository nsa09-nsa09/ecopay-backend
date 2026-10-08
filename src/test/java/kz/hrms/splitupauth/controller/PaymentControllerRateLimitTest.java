package kz.hrms.splitupauth.controller;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import kz.hrms.splitupauth.dto.ConfirmPaymentRequest;
import kz.hrms.splitupauth.dto.CreatePaymentIntentRequest;
import kz.hrms.splitupauth.dto.PaymentIntentResponse;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.TooManyRequestsException;
import kz.hrms.splitupauth.service.PaymentHistoryService;
import kz.hrms.splitupauth.service.PaymentService;
import kz.hrms.splitupauth.service.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** B5: rate limiting on payment-intent creation (hard 429) and confirm-success (soft fallback). */
class PaymentControllerRateLimitTest {

  private PaymentService paymentService;
  private RateLimiter rateLimiter;
  private PaymentController controller;
  private User user;

  @BeforeEach
  void setUp() {
    paymentService = mock(PaymentService.class);
    PaymentHistoryService historyService = mock(PaymentHistoryService.class);
    rateLimiter = mock(RateLimiter.class);
    controller = new PaymentController(paymentService, historyService, rateLimiter);
    ReflectionTestUtils.setField(controller, "intentMax", 10);
    ReflectionTestUtils.setField(controller, "intentWindowSeconds", 600L);
    ReflectionTestUtils.setField(controller, "confirmPerIntentWindowSeconds", 3L);
    ReflectionTestUtils.setField(controller, "confirmUserMaxPerMinute", 20);
    user = mock(User.class);
    when(user.getId()).thenReturn(7L);
  }

  @Test
  void createIntentOverLimitReturns429AndNeverReachesService() {
    doThrow(new TooManyRequestsException("too many"))
        .when(rateLimiter)
        .check(contains("payment-intent:7"), eq(10), eq(600L), anyString());

    assertThrows(
        TooManyRequestsException.class,
        () -> controller.createPaymentIntent(5L, user, new CreatePaymentIntentRequest()));
    verify(paymentService, never()).createPaymentIntent(anyLong(), any(), any());
  }

  @Test
  void confirmSuccessUnderLimitCallsProvider() {
    doNothing().when(rateLimiter).check(anyString(), anyInt(), anyLong(), anyString());
    PaymentIntentResponse live = mock(PaymentIntentResponse.class);
    when(paymentService.confirmPaymentSuccess(eq(9L), eq(user), any())).thenReturn(live);

    var body = controller.confirmPaymentSuccess(9L, user, new ConfirmPaymentRequest()).getBody();

    assertSame(live, body);
    verify(paymentService).confirmPaymentSuccess(eq(9L), eq(user), any());
    verify(paymentService, never()).getPaymentIntent(anyLong(), any());
  }

  @Test
  void confirmSuccessOverLimitReturnsDbStateWithoutCallingProvider() {
    doThrow(new TooManyRequestsException("throttled"))
        .when(rateLimiter)
        .check(contains("payment-confirm:"), anyInt(), anyLong(), anyString());
    PaymentIntentResponse dbState = mock(PaymentIntentResponse.class);
    when(paymentService.getPaymentIntent(9L, user)).thenReturn(dbState);

    var body = controller.confirmPaymentSuccess(9L, user, new ConfirmPaymentRequest()).getBody();

    // Over the soft limit: DB state is returned, FreedomPay is never contacted, and it is NOT a
    // 429.
    assertSame(dbState, body);
    verify(paymentService).getPaymentIntent(9L, user);
    verify(paymentService, never()).confirmPaymentSuccess(anyLong(), any(), any());
  }
}
