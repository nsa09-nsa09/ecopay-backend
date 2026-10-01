package kz.hrms.splitupauth.repository;

import java.time.LocalDateTime;
import java.util.List;
import kz.hrms.splitupauth.entity.PriceWatchProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
          + "AND (p.leaseUntil IS NULL OR p.leaseUntil <= :now) "
          + "ORDER BY p.id ASC")
  List<PriceWatchProvider> findDueForCheck(@Param("now") LocalDateTime now);

  @Modifying
  @Transactional
  @Query(
      value =
          """
          UPDATE price_watch_provider
          SET lease_owner = :owner, lease_until = :leaseUntil, updated_at = NOW()
          WHERE id = :id
            AND active = TRUE
            AND (lease_until IS NULL OR lease_until <= :now)
          """,
      nativeQuery = true)
  int claim(
      @Param("id") Long id,
      @Param("owner") String owner,
      @Param("leaseUntil") LocalDateTime leaseUntil,
      @Param("now") LocalDateTime now);

  @Modifying
  @Transactional
  @Query(
      value =
          """
          UPDATE price_watch_provider
          SET lease_owner = NULL, lease_until = NULL, updated_at = NOW()
          WHERE id = :id AND lease_owner = :owner
          """,
      nativeQuery = true)
  int release(@Param("id") Long id, @Param("owner") String owner);
}
