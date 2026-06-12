package kz.hrms.splitupauth.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_email", columnList = "email")
})
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = "password")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(unique = true)
    private String phone;

    @Column(name = "phone_verified_at")
    private LocalDateTime phoneVerifiedAt;

    private String avatar;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @Column(nullable = false)
    @Builder.Default
    private Integer reputation = 0;

    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private Boolean emailVerified = false;

    @Column(name = "owner_verified", nullable = false)
    @Builder.Default
    private Boolean ownerVerified = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();

        if (status == null) {
            status = UserStatus.ACTIVE;
        }

        if (role == null) {
            role = Role.USER;
        }

        if (reputation == null) {
            reputation = 0;
        }

        if (emailVerified == null) {
            emailVerified = false;
        }

        if (ownerVerified == null) {
            ownerVerified = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
