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
public class AdminActionLogDto {
    private Long id;
    private String eventId;
    private Long adminUserId;
    private String actionType;
    private String entityType;
    private Long entityId;
    private String reason;
    private String ipAddress;
    private String userAgent;
    private LocalDateTime createdAt;
}