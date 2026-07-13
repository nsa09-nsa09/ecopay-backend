package kz.hrms.splitupauth.repository;

import java.util.Optional;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
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
}
