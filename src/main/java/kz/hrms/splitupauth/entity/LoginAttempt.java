package kz.hrms.splitupauth.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "login_attempts", indexes = {
        @Index(name = "idx_email_attempt", columnList = "email, attempt_time")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    @Column(name = "attempt_time", nullable = false)
    private LocalDateTime attemptTime;

    @Column(nullable = false)
    private Boolean successful;

    @PrePersist
    protected void onCreate() {
        attemptTime = LocalDateTime.now();
    }
}
