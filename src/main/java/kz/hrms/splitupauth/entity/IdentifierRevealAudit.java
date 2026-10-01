package kz.hrms.splitupauth.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Table(
    name = "identifier_reveal_audit",
    indexes = {
      @Index(
          name = "idx_identifier_reveal_audit_actor_created",
          columnList = "actor_user_id,created_at"),
      @Index(
          name = "idx_identifier_reveal_audit_member_created",
          columnList = "room_member_id,created_at"),
      @Index(name = "idx_identifier_reveal_audit_context", columnList = "context_type,context_id"),
      @Index(
          name = "idx_identifier_reveal_audit_outcome_created",
          columnList = "outcome,created_at")
    })
@Immutable
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdentifierRevealAudit {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "event_id", nullable = false, unique = true)
  private UUID eventId;

  @Column(name = "correlation_id")
  private UUID correlationId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "actor_user_id", nullable = false)
  private User actorUser;

  @Column(name = "actor_role", nullable = false, length = 20)
  private String actorRole;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "room_id", nullable = false)
  private Room room;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "room_member_id", nullable = false)
  private RoomMember roomMember;

  @Enumerated(EnumType.STRING)
  @Column(name = "context_type", length = 30)
  private IdentifierRevealContextType contextType;

  @Column(name = "context_id")
  private Long contextId;

  @Enumerated(EnumType.STRING)
  @Column(name = "reason_code", nullable = false, length = 50)
  private IdentifierRevealReasonCode reasonCode;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private IdentifierRevealOutcome outcome;

  @Column(name = "request_id")
  private UUID requestId;

  @Column(name = "client_ip", length = 64)
  private String clientIp;

  @Column(name = "user_agent", length = 255)
  private String userAgent;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @PrePersist
  protected void onCreate() {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
    if (createdAt == null) {
      createdAt = LocalDateTime.now();
    }
  }
}
