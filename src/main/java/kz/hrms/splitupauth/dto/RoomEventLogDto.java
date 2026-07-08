package kz.hrms.splitupauth.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomEventLogDto {
  private Long id;
  private String eventId;
  private Long actorUserId;
  private String actorDisplayName;
  private String actorRole;
  private Long roomId;
  private Long roomOwnerUserId;
  private String roomOwnerDisplayName;
  private Long roomMemberId;
  private String eventType;
  private JsonNode oldState;
  private JsonNode newState;
  private String ipAddress;
  private String userAgent;
  private LocalDateTime createdAt;
}
