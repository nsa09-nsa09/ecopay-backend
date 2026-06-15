package kz.hrms.splitupauth.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "site_visit", uniqueConstraints = {
        @UniqueConstraint(name = "uq_site_visit_visitor_date", columnNames = {"visitor_id", "visit_date"})
}, indexes = {
        @Index(name = "idx_site_visit_visit_date", columnList = "visit_date"),
        @Index(name = "idx_site_visit_visitor_id", columnList = "visitor_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SiteVisit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "visitor_id", nullable = false, columnDefinition = "uuid")
    private UUID visitorId;

    @Column(name = "visit_date", nullable = false)
    private LocalDate visitDate;

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "page_count", nullable = false)
    @Builder.Default
    private Integer pageCount = 1;

    @Column(name = "is_authenticated", nullable = false)
    @Builder.Default
    private Boolean isAuthenticated = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "last_path", length = 255)
    private String lastPath;
}
