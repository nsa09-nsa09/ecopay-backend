package kz.hrms.splitupauth.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "room_member_identifiers")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomMemberIdentifier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_member_id", nullable = false, unique = true)
    private RoomMember roomMember;

    @Enumerated(EnumType.STRING)
    @Column(name = "identifier_type", nullable = false)
    private IdentifierType identifierType;

    @Column(name = "identifier_encrypted", nullable = false, columnDefinition = "text")
    private String identifierEncrypted;

    @Column(name = "identifier_masked", nullable = false, length = 50)
    private String identifierMasked;

    @Column(name = "is_valid_format", nullable = false)
    @Builder.Default
    private Boolean isValidFormat = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (isValidFormat == null) {
            isValidFormat = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}