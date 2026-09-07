package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import kz.hrms.splitupauth.entity.RefundReasonCode;
import kz.hrms.splitupauth.entity.RefundRequestStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RefundRequestResponse {
  private Long id;
  private Long paymentTransactionId;
  private RefundReasonCode reasonCode;
  private String description;
  private RefundRequestStatus status;
  private BigDecimal approvedAmount;
  private String decisionNote;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
  private LocalDateTime decidedAt;
}
