package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.News;
import kz.hrms.splitupauth.entity.NewsStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NewsRepository extends JpaRepository<News, Long> {

  /**
   * Public feed: only published items, sorted by sort_order ascending then published_at descending.
   * Caller passes the page request; the sort is embedded here so callers can't accidentally fall
   * back to insertion order.
   */
  Page<News> findByStatusOrderBySortOrderAscPublishedAtDesc(NewsStatus status, Pageable pageable);

  /** Admin list: every status, newest first. */
  Page<News> findAllByOrderByCreatedAtDesc(Pageable pageable);

  /** Admin list filtered by status, newest first. */
  Page<News> findByStatusOrderByCreatedAtDesc(NewsStatus status, Pageable pageable);
}
