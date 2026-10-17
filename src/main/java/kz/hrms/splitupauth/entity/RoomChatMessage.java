package kz.hrms.splitupauth.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One message in a room's post-payment chat. Access to the chat is gated in the service layer
 * (owner + PENDING/ACTIVE members only); the row itself carries no status.
 */
@Entity
@Table(
    name = "room_chat_messages",
    indexes = {
      @Index(name = "idx_room_chat_messages_room_created", columnList = "room_id,created_at")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomChatMessage {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "room_id", nullable = false)
  private Room room;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "sender_user_id", nullable = false)
  private User senderUser;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String body;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @PrePersist
  protected void onCreate() {
    createdAt = LocalDateTime.now();
  }
}
