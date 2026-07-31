package kz.hrms.splitupauth.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "payment_reservations",
    indexes = {
      @Index(name = "idx_payment_reservations_room_status_expires", columnList = "room_id,status,expires_at"),
      @Index(name = "idx_payment_reservations_member_status", columnList = "room_member_id,status")
    },
    uniqueConstraints = {
      @UniqueConstraint(name = "uq_payment_reservations_intent", columnNames = "payment_intent_id")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentReservation {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "payment_intent_id", nullable = false)
  private PaymentIntent paymentIntent;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "room_member_id", nullable = false)
  private RoomMember roomMember;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "room_id", nullable = false)
  private Room room;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  @Builder.Default
  private PaymentReservationStatus status = PaymentReservationStatus.RESERVED;

  @Column(name = "expires_at", nullable = false)
  private LocalDateTime expiresAt;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "consumed_at")
  private LocalDateTime consumedAt;

  @Column(name = "released_at")
  private LocalDateTime releasedAt;

  @Column(name = "release_reason", length = 80)
  private String releaseReason;

  @PrePersist
  protected void onCreate() {
    if (createdAt == null) {
      createdAt = LocalDateTime.now();
    }
    if (status == null) {
      status = PaymentReservationStatus.RESERVED;
    }
  }
}
