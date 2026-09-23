package kz.hrms.splitupauth.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
  Optional<User> findByEmail(String email);

  boolean existsByEmail(String email);

  Optional<User> findByEmailAndStatus(String email, UserStatus status);

  Optional<User> findByPhone(String phone);

  boolean existsByPhone(String phone);

  Optional<User> findByPublicId(String publicId);

  boolean existsByPublicId(String publicId);

  Optional<User> findBySlug(String slug);

  boolean existsBySlugAndIdNot(String slug, Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from User u where u.id = :id")
  Optional<User> findByIdForUpdate(@Param("id") Long id);

  @Query(
      "select u.id from User u where u.status = kz.hrms.splitupauth.entity.UserStatus.ACTIVE and u.banStartsAt <= :now")
  List<Long> findDueBanActivationIds(@Param("now") LocalDateTime now, Pageable pageable);

  @Query(
      "select u.id from User u where u.status = kz.hrms.splitupauth.entity.UserStatus.BANNED and u.banUntil <= :now")
  List<Long> findDueBanExpirationIds(@Param("now") LocalDateTime now, Pageable pageable);
}
