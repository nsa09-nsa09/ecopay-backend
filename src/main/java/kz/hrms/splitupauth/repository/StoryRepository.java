package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.Story;
import kz.hrms.splitupauth.entity.StoryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StoryRepository extends JpaRepository<Story, Long> {

  Page<Story> findByStatusOrderBySortOrderAscPublishedAtDesc(
      StoryStatus status, Pageable pageable);

  Page<Story> findAllByOrderByCreatedAtDesc(Pageable pageable);

  Page<Story> findByStatusOrderByCreatedAtDesc(StoryStatus status, Pageable pageable);
}
