package kz.hrms.splitupauth.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway;
import kz.hrms.splitupauth.service.FreedomWebhookInboxCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
class FreedomPayWebhookControllerTest {

  @Mock private FreedomPayGateway gateway;
  @Mock private FreedomWebhookInboxCoordinator coordinator;

  private FreedomPayWebhookController controller;

  @BeforeEach
  void setUp() {
    controller = new FreedomPayWebhookController(gateway, coordinator);
  }

  @Test
  void durableAccept_isAcknowledged() {
    Map<String, String> params = Map.of("pg_order_id", "42", "pg_sig", "valid");
    when(coordinator.acceptAndProcess("result", params))
        .thenReturn(new FreedomWebhookInboxCoordinator.Acceptance(7L, false));
    when(gateway.buildWebhookResponse("result", "ok", "Order processed"))
        .thenReturn("<ok/>");

    var response = controller.result(params);

    assertEquals("<ok/>", response.getBody());
    verify(gateway).buildWebhookResponse("result", "ok", "Order processed");
  }

  @Test
  void storageFailure_isNotAcknowledgedSoProviderCanRetry() {
    Map<String, String> params = Map.of("pg_order_id", "42", "pg_sig", "valid");
    when(coordinator.acceptAndProcess("result", params))
        .thenThrow(new DataAccessResourceFailureException("database unavailable"));
    when(gateway.buildWebhookResponse("result", "error", "temporarily unavailable"))
        .thenReturn("<retry/>");

    var response = controller.result(params);

    assertEquals("<retry/>", response.getBody());
    verify(gateway)
        .buildWebhookResponse("result", "error", "temporarily unavailable");
  }
}
