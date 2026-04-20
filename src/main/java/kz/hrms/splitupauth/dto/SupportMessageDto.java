package kz.hrms.splitupauth.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SupportMessageDto {
    private Long id;
    private Long senderUserId;
    private String senderRole;
    private String message;
    private String attachmentUrl;
    private LocalDateTime createdAt;
}