package kz.hrms.splitupauth.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.DeletedUserIdentityArchive;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeletedUserIdentityArchiveRepository
    extends JpaRepository<DeletedUserIdentityArchive, Long> {
  boolean existsByUser_Id(Long userId);

  Optional<DeletedUserIdentityArchive> findByUser_Id(Long userId);

  List<DeletedUserIdentityArchive> findByUser_IdIn(Collection<Long> userIds);
}
