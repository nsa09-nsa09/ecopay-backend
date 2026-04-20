package kz.hrms.splitupauth.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "rooms", indexes = {
        @Index(name = "idx_rooms_status_start_date", columnList = "status,start_date"),
        @Index(name = "idx_rooms_owner_created_at", columnList = "owner_user_id,created_at"),
        @Index(name = "idx_rooms_service_status_start_date", columnList = "service_id,status,start_date")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", nullable = false)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private ServiceEntity service;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tariff_plan_id")
    private TariffPlan tariffPlan;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", nullable = false, length = 20)
    private RoomType roomType;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_mode", nullable = false, length = 30)
    private VerificationMode verificationMode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RoomStatus status;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "max_members", nullable = false)
    private Integer maxMembers;

    @Column(name = "price_total", precision = 12, scale = 2)
    private BigDecimal priceTotal;

    @Column(name = "price_per_member", precision = 12, scale = 2)
    private BigDecimal pricePerMember;

    @Column(nullable = false, length = 10)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", nullable = false, length = 20)
    private PeriodType periodType;

    @Column(name = "start_date", nullable = false)
    private LocalDateTime startDate;

    @Column(name = "cancellation_policy", columnDefinition = "text")
    private String cancellationPolicy;

    @Column(name = "provider_name", length = 120)
    private String providerName;

    @Column(name = "tariff_name_snapshot", length = 150)
    private String tariffNameSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "connection_type", length = 20)
    private ConnectionType connectionType;

    @Column(name = "operator_restrictions", columnDefinition = "text")
    private String operatorRestrictions;

    @Column(name = "operator_terms_confirmed", nullable = false)
    @Builder.Default
    private Boolean operatorTermsConfirmed = false;

    @Column(name = "ready_for_verification_at")
    private LocalDateTime readyForVerificationAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "blocked_at")
    private LocalDateTime blockedAt;

    @Column(name = "block_reason", columnDefinition = "text")
    private String blockReason;

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

        if (verificationMode == null) {
            verificationMode = VerificationMode.RISK_BASED;
        }

        if (status == null) {
            status = RoomStatus.OPEN;
        }

        if (currency == null) {
            currency = "KZT";
        }

        if (operatorTermsConfirmed == null) {
            operatorTermsConfirmed = false;
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