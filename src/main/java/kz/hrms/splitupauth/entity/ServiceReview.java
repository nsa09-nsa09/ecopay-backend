package kz.hrms.splitupauth.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * User-facing testimonial about the EcoPay service (used by the homepage
 * carousel). Different from {@link Review}, which is the per-room peer
 * rating between members. Exactly one row per user is allowed
 * (author_id UNIQUE at the DB level), enforced via {@link kz.hrms.splitupauth.repository.ServiceReviewRepository#findByAuthor}.
 */
@Entity
@Table(name = "service_reviews", indexes = {
        @Index(name = "idx_service_reviews_featured_created",
                columnList = "featured, created_at DESC")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false, unique = true)
    private User author;

    @Column(nullable = false)
    private Integer rating;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(name = "featured", nullable = false)
    @Builder.Default
    private Boolean featured = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (featured == null) {
            featured = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
