package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ModerationQueueItemDto {
  private Long id;
  private String entityType;
  private Long entityId;
  private Long roomId;
  private Long roomMemberId;
  private String reasonCode;
  private BigDecimal riskScore;
  private String status;
  private Long assignedAdminId;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  /**
   * B13: admin-facing context so the moderation page can tell "what is this about" without a second
   * lookup. All fields are nullable — a queue entry is not guaranteed to carry a room / member.
   */
  private String roomTitle;

  private String serviceName;
  private String roomStatus;
  private PartyRef owner;
  private MemberCtx member;
  private BigDecimal paidAmountKzt;
  private String paymentStatus;

  /**
   * Russian description of {@link #reasonCode}, picked from the known codes in ModerationService.
   */
  private String reasonDescription;

  @Data
  @Builder
  public static class PartyRef {
    private Long id;
    private String displayName;
    private String publicId;
  }

  @Data
  @Builder
  public static class MemberCtx {
    private Long id;
    private Long userId;
    private String displayName;
    private String publicId;
    private String status;
    private Boolean identifierValid;
    private LocalDateTime ownerAccessConfirmedAt;
    private LocalDateTime memberConfirmedAt;
  }
}
