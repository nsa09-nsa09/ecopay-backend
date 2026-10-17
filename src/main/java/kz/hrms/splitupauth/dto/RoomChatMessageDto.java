package kz.hrms.splitupauth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import kz.hrms.splitupauth.entity.RoomChatMessage;
import kz.hrms.splitupauth.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A room chat message on the wire. Doubles as the STOMP push payload, so the live event and a
 * persisted-history row have identical shape.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RoomChatMessageDto {

  private Long id;
  private Long roomId;
  private Long senderId;
  private String senderPublicId;
  private String senderName;
  private String senderAvatar;
  private boolean owner;
  private String body;
  private LocalDateTime createdAt;

  public static RoomChatMessageDto from(RoomChatMessage m) {
    User sender = m.getSenderUser();
    boolean isOwner =
        sender != null
            && m.getRoom().getOwner() != null
            && sender.getId() != null
            && sender.getId().equals(m.getRoom().getOwner().getId());

    // Sender may have been soft-deleted/anonymized; keep the transcript coherent.
    String name = sender != null && sender.getDisplayName() != null ? sender.getDisplayName() : "—";

    return RoomChatMessageDto.builder()
        .id(m.getId())
        .roomId(m.getRoom().getId())
        .senderId(sender != null ? sender.getId() : null)
        .senderPublicId(sender != null ? sender.getPublicId() : null)
        .senderName(name)
        .senderAvatar(sender != null ? sender.getAvatar() : null)
        .owner(isOwner)
        .body(m.getBody())
        .createdAt(m.getCreatedAt())
        .build();
  }
}
