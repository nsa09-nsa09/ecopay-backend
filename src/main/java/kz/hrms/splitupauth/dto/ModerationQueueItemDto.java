package kz.hrms.splitupauth.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
}