package kz.hrms.splitupauth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "room_settings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomSettings {

  @Id private Long id;

  @Column(name = "minimum_room_members", nullable = false)
  private Integer minimumRoomMembers;

  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  @PreUpdate
  protected void onUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
