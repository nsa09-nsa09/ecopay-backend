package kz.hrms.splitupauth.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Row-level Freedom Pay webhook processing view for the admin finance drill-down. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinanceWebhookDto {
  private Long id;
  private LocalDateTime receivedAt;
  private LocalDateTime lastAttemptAt;
  private LocalDateTime processedAt;
  private LocalDateTime nextRetryAt;
  private LocalDateTime deadLetteredAt;
  private String processingStatus;
  private String callbackScript;
  private String providerRequestId;
  private Boolean signatureValid;
  private Integer attemptCount;
  private String lastErrorCode;
  private String errorMessage;
}
