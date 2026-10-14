package kz.hrms.splitupauth.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SupportTicketResponse {
  private Long id;
  private Long userId;
  private Long roomId;
  private String roomTitle;
  private Long roomMemberId;
  private String subject;
  private String topic;
  private String status;
  private String priority;
  private Boolean escalatedToDispute;

  /**
   * Id of the Dispute this ticket was escalated into, or null if it was never escalated. Added for
   * B2 so the admin UI can link directly from the ticket to the dispute case.
   */
  private Long disputeId;

  private Long assignedAdminId;
  private String assignedAdminDisplayName;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
  private LocalDateTime closedAt;
  private List<SupportMessageDto> messages;
}
