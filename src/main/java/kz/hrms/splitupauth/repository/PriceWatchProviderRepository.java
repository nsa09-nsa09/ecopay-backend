package kz.hrms.splitupauth.repository;

import java.time.LocalDateTime;
import java.util.List;
import kz.hrms.splitupauth.entity.PriceWatchProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PriceWatchProviderRepository extends JpaRepository<PriceWatchProvider, Long> {

  List<PriceWatchProvider> findAllByOrderByIdAsc();

  /**
   * Batch that the scheduler tick pulls: everything active whose {@code nextCheckAt} has come due
   * (or has never been set). Deterministic order — id — so the per-domain limiter always sees the
   * same provider first if two of them clash.
   */
  @Query(
      "SELECT p FROM PriceWatchProvider p "
          + "WHERE p.active = true "
          + "AND (p.nextCheckAt IS NULL OR p.nextCheckAt <= :now) "
          + "ORDER BY p.id ASC")
  List<PriceWatchProvider> findDueForCheck(@Param("now") LocalDateTime now);
}
