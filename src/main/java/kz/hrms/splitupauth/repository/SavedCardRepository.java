package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.SavedCard;
import kz.hrms.splitupauth.entity.SavedCardStatus;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SavedCardRepository extends JpaRepository<SavedCard, Long> {

    List<SavedCard> findByUserAndStatusOrderByIsDefaultDescCreatedAtDesc(
            User user, SavedCardStatus status);

    Optional<SavedCard> findByUserAndProviderTokenAndProviderName(
            User user, String providerToken, String providerName);

    Optional<SavedCard> findByUserAndIsDefaultTrueAndStatus(User user, SavedCardStatus status);
}
