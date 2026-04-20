package kz.hrms.splitupauth.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "room_members", indexes = {
        @Index(name = "idx_room_members_room_status", columnList = "room_id,status"),
        @Index(name = "idx_room_members_user_created_at", columnList = "user_id,created_at"),
        @Index(name = "idx_room_members_requires_admin_review", columnList = "requires_admin_review,status")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_room_member", columnNames = {"room_id", "user_id"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberStatus status;

    @Column(name = "requires_admin_review", nullable = false)
    @Builder.Default
    private Boolean requiresAdminReview = false;

    @Column(name = "access_method", length = 50)
    private String accessMethod;

    @Column(name = "owner_access_confirmed_at")
    private LocalDateTime ownerAccessConfirmedAt;

    @Column(name = "member_confirmed_at")
    private LocalDateTime memberConfirmedAt;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "payment_intent_id")
    private Long paymentIntentId;

    @Column(name = "latest_payment_tx_id")
    private Long latestPaymentTxId;

    @Column(name = "consent_accepted_at")
    private LocalDateTime consentAcceptedAt;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();

        if (status == null) {
            status = MemberStatus.APPLIED;
        }

        if (requiresAdminReview == null) {
            requiresAdminReview = false;
        }

        if (version == null) {
            version = 0L;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}