package kz.hrms.splitupauth.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;
import org.hibernate.annotations.Immutable;

@Entity
@Table(name = "deleted_user_identity_archive")
@Immutable
@Data
public class DeletedUserIdentityArchive {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false, unique = true)
  private User user;

  @Column(columnDefinition = "TEXT")
  private String emailEncrypted;

  @Column(columnDefinition = "TEXT")
  private String phoneEncrypted;

  @Column(length = 255)
  private String emailMasked;

  @Column(length = 50)
  private String phoneMasked;

  @Column(length = 30)
  private String slugAtDeletion;

  @Column(length = 255)
  private String displayNameAtDeletion;

  private LocalDateTime deletedAt;
  private LocalDateTime archivedAt;
}
