package kz.hrms.splitupauth.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "tariff_plans", indexes = {
        @Index(name = "idx_tariff_plans_service_id", columnList = "service_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TariffPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private ServiceEntity service;

    @Column(nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", nullable = false)
    private PeriodType periodType;

    @Column(name = "max_members", nullable = false)
    private Integer maxMembers;

    @Column(name = "base_price_total", precision = 12, scale = 2)
    private BigDecimal basePriceTotal;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String currency = "KZT";

    @Enumerated(EnumType.STRING)
    @Column(name = "connection_type")
    private ConnectionType connectionType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "operator_rules", columnDefinition = "jsonb")
    private String operatorRules;

    /**
     * "Плюшки" — list of human-readable perks shown next to the price
     * (e.g., "4K", "без рекламы"). Stored as a JSONB array of strings so the
     * admin can edit a free-form list without a side table.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "features", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> features = new ArrayList<>();

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (currency == null) {
            currency = "KZT";
        }
        if (isActive == null) {
            isActive = true;
        }
        if (features == null) {
            features = new ArrayList<>();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        if (features == null) {
            features = new ArrayList<>();
        }
    }
}
