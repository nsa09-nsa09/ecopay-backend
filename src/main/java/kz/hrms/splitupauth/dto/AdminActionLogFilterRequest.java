package kz.hrms.splitupauth.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminActionLogFilterRequest {
    private String entityType;
    private Long actorUserId;
    private LocalDateTime dateFrom;
    private LocalDateTime dateTo;
}