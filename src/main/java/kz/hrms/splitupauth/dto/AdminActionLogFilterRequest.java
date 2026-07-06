package kz.hrms.splitupauth.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class AdminActionLogFilterRequest {
  private String entityType;
  private Long actorUserId;
  private LocalDateTime dateFrom;
  private LocalDateTime dateTo;
}
