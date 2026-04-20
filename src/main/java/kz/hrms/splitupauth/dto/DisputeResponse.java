package kz.hrms.splitupauth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DisputeResponse {
    private Long id;
    private Long roomId;
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
}