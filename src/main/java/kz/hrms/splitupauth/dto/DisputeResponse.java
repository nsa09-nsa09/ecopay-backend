package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DisputeResponse {
  private Long id;
  private Long roomId;
  private String roomTitle;
  private String serviceName;
  private Long roomMemberId;
  private Long ticketId;
  private Long openedByUserId;
  private Long assignedAdminId;
  private String reasonCode;
  private String description;
  private String status;
  private String decision;
  private String decisionComment;
  private LocalDateTime resolvedAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  /**
   * Admin-facing context fields added for B3 so the admin UI shows who the dispute is between and
   * how much money is involved without a second round of lookups.
   */
  private PartyRef owner;

  private MemberRef member;
  private PartyRef openedBy;
  private BigDecimal paidAmountKzt;
  private BigDecimal refundedAmountKzt;
  private BigDecimal refundableAmountKzt;
  private Boolean refundTriggered;

  private java.util.List<kz.hrms.splitupauth.dto.SupportMessageDto> ticketMessages;
  private java.util.List<kz.hrms.splitupauth.dto.DisputeStatementDto> statements;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class PartyRef {
    private Long id;
    private String displayName;
    private String publicId;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class MemberRef {
    private Long id;
    private Long userId;
    private String displayName;
    private String publicId;
    private String status;
  }
}
