package kz.hrms.splitupauth.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class RoomEventLogFilterRequest {
  private Long roomId;
  private Long actorUserId;
  private String eventType;
  private LocalDateTime dateFrom;
  private LocalDateTime dateTo;
}
