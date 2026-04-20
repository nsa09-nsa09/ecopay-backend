package kz.hrms.splitupauth.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RoomEventLogFilterRequest {
    private Long roomId;
    private Long actorUserId;
    private String eventType;
    private LocalDateTime dateFrom;
    private LocalDateTime dateTo;
}