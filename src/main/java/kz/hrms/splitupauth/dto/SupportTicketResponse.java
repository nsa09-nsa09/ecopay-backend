package kz.hrms.splitupauth.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class SupportTicketResponse {
    private Long id;
    private Long userId;
    private Long roomId;
    private Long roomMemberId;
    private String subject;
    private String topic;
    private String status;
    private String priority;
    private Boolean escalatedToDispute;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime closedAt;
    private List<SupportMessageDto> messages;
}