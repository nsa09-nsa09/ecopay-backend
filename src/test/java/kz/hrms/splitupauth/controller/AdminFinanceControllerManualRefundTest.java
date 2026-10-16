package kz.hrms.splitupauth.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import kz.hrms.splitupauth.dto.CreateRefundRequest;
import kz.hrms.splitupauth.dto.RefundTransactionResponse;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.RefundService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class AdminFinanceControllerManualRefundTest {

  @Mock private RefundService refundService;

  @Test
  void manualRefundEndpointDelegatesToRefundService() {
    AdminFinanceController controller =
        new AdminFinanceController(null, null, null, null, null, refundService);
    CreateRefundRequest request = new CreateRefundRequest();
    User admin = User.builder().id(1L).role(Role.ADMIN).build();
    HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    RefundTransactionResponse expected = RefundTransactionResponse.builder().id(7L).build();
    when(refundService.createRefund(admin, request, httpRequest)).thenReturn(expected);

    ResponseEntity<RefundTransactionResponse> response =
        controller.createManualRefund(request, admin, httpRequest);

    assertEquals(200, response.getStatusCode().value());
    assertEquals(7L, response.getBody().getId());
    verify(refundService).createRefund(admin, request, httpRequest);
  }
}
