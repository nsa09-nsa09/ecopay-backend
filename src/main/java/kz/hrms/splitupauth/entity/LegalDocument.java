package kz.hrms.splitupauth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Editable legal document (Terms of Service / Privacy consent) shown on the
 * registration page and public /terms and /privacy pages. Modeled after
 * {@link SiteContent} but there is one row per doc_type value.
 */
@Entity
@Table(name = "legal_documents")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LegalDocument {

    public enum DocType {
        TERMS,
        PRIVACY
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, unique = true, length = 32)
    private DocType docType;

    /** Bumped by +1 on every admin save. Persisted on the acceptance side too. */
    @Column(nullable = false)
    private Integer version;

    @Column(name = "title_kz", columnDefinition = "TEXT")
    private String titleKz;

    @Column(name = "title_ru", columnDefinition = "TEXT")
    private String titleRu;

    @Column(name = "title_en", columnDefinition = "TEXT")
    private String titleEn;

    @Column(name = "body_kz", columnDefinition = "TEXT")
    private String bodyKz;

    @Column(name = "body_ru", columnDefinition = "TEXT")
    private String bodyRu;

    @Column(name = "body_en", columnDefinition = "TEXT")
    private String bodyEn;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    @PrePersist
    @PreUpdate
    void touchUpdatedAt() {
        this.updatedAt = LocalDateTime.now();
        if (this.version == null) {
            this.version = 1;
        }
    }
}
