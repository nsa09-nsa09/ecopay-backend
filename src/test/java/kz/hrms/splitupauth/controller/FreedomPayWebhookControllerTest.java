package kz.hrms.splitupauth.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway;
import kz.hrms.splitupauth.service.FreedomWebhookInboxCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@ExtendWith(MockitoExtension.class)
class FreedomPayWebhookControllerTest {

  @Mock private FreedomPayGateway gateway;
  @Mock private FreedomWebhookInboxCoordinator coordinator;

  private FreedomPayWebhookController controller;

  @BeforeEach
  void setUp() {
    controller = new FreedomPayWebhookController(gateway, coordinator);
  }

  private static MultiValueMap<String, String> form(String... kv) {
    MultiValueMap<String, String> m = new LinkedMultiValueMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.add(kv[i], kv[i + 1]);
    }
    return m;
  }

  @Test
  void durableAccept_isAcknowledged() {
    MultiValueMap<String, String> params = form("pg_order_id", "42", "pg_sig", "valid");
    Map<String, List<String>> expected =
        Map.of("pg_order_id", List.of("42"), "pg_sig", List.of("valid"));
    when(coordinator.acceptAndProcessMulti("result", expected))
        .thenReturn(new FreedomWebhookInboxCoordinator.Acceptance(7L, false));
    when(gateway.buildWebhookResponse("result", "ok", "Order processed")).thenReturn("<ok/>");

    var response = controller.result(params);

    assertEquals("<ok/>", response.getBody());
    verify(gateway).buildWebhookResponse("result", "ok", "Order processed");
  }

  @Test
  void storageFailure_isNotAcknowledgedSoProviderCanRetry() {
    MultiValueMap<String, String> params = form("pg_order_id", "42", "pg_sig", "valid");
    when(coordinator.acceptAndProcessMulti(anyString(), anyMap()))
        .thenThrow(new DataAccessResourceFailureException("database unavailable"));
    when(gateway.buildWebhookResponse("result", "error", "temporarily unavailable"))
        .thenReturn("<retry/>");

    var response = controller.result(params);

    assertEquals("<retry/>", response.getBody());
    verify(gateway).buildWebhookResponse("result", "error", "temporarily unavailable");
  }

  @Test
  void invalidSignature_isAnsweredWithSignedError() {
    when(coordinator.acceptAndProcessMulti(anyString(), anyMap()))
        .thenReturn(new FreedomWebhookInboxCoordinator.Acceptance(9L, true));
    when(gateway.buildWebhookResponse("payout-result", "error", "invalid signature"))
        .thenReturn("<bad/>");

    assertEquals("<bad/>", controller.payoutResult(form("pg_payment_id", "1")).getBody());
  }

  @Test
  void repeatedSignedFieldsAreForwardedWithoutCollapsing() {
    MultiValueMap<String, String> params =
        form("pg_receipt", "a", "pg_receipt", "b", "pg_sig", "s");
    Map<String, List<String>> expected =
        Map.of("pg_receipt", List.of("a", "b"), "pg_sig", List.of("s"));
    when(coordinator.acceptAndProcessMulti("result", expected))
        .thenReturn(new FreedomWebhookInboxCoordinator.Acceptance(1L, false));
    when(gateway.buildWebhookResponse("result", "ok", "Order processed")).thenReturn("<ok/>");

    assertEquals("<ok/>", controller.result(params).getBody());
  }

  @Test
  void payoutCardCallbackUsesItsOwnScript() {
    when(coordinator.acceptAndProcessMulti(anyString(), anyMap()))
        .thenReturn(new FreedomWebhookInboxCoordinator.Acceptance(3L, false));
    when(gateway.buildWebhookResponse("payout-card-result", "ok", "Order processed"))
        .thenReturn("<ok/>");

    controller.payoutCardResult(form("pg_payment_id", "bind-1", "pg_sig", "s"));

    verify(coordinator)
        .acceptAndProcessMulti(
            "payout-card-result",
            Map.of("pg_payment_id", List.of("bind-1"), "pg_sig", List.of("s")));
  }

  @Test
  void oversizedCallbackIsRejectedBeforeStorage() {
    when(gateway.buildWebhookResponse("result", "error", "payload too large")).thenReturn("<big/>");

    var response = controller.result(form("pg_junk", "x".repeat(40_000)));

    assertEquals("<big/>", response.getBody());
    verify(coordinator, never()).acceptAndProcessMulti(anyString(), anyMap());
  }
}
