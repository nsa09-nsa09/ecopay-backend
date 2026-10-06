package kz.hrms.splitupauth.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.SavedCard;
import kz.hrms.splitupauth.entity.SavedCardStatus;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SavedCardRepository extends JpaRepository<SavedCard, Long> {

  /** Account deletion: saved purchase cards can never be charged again. */
  @Modifying
  @Query(
      "update SavedCard c set c.status = :revoked, c.isDefault = false, c.revokedAt = :now"
          + " where c.user.id = :userId and c.status <> :revoked")
  int revokeAllForUser(
      @Param("userId") Long userId,
      @Param("revoked") SavedCardStatus revoked,
      @Param("now") LocalDateTime now);

  List<SavedCard> findByUserAndStatusOrderByIsDefaultDescCreatedAtDesc(
      User user, SavedCardStatus status);

  Optional<SavedCard> findByUserAndProviderTokenAndProviderName(
      User user, String providerToken, String providerName);

  Optional<SavedCard> findByUserAndIsDefaultTrueAndStatus(User user, SavedCardStatus status);
}
