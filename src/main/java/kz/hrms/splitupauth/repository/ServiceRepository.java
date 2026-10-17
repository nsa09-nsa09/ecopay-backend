package kz.hrms.splitupauth.repository;

import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.ServiceEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ServiceRepository extends JpaRepository<ServiceEntity, Long> {
  List<ServiceEntity> findByIsActiveTrueOrderByIdAsc();

  List<ServiceEntity> findByCategoryIdAndIsActiveTrueOrderByIdAsc(Long categoryId);

  List<ServiceEntity> findAllByOrderByIdAsc();

  List<ServiceEntity> findByCategoryIdOrderByIdAsc(Long categoryId);

  Optional<ServiceEntity> findBySlug(String slug);

  boolean existsBySlug(String slug);

  boolean existsByCategoryIdAndIsActiveTrue(Long categoryId);

  long countByCategoryId(Long categoryId);

  /**
   * Case-insensitive substring match on service name OR V95 search_aliases, scoped to active
   * services. Backs the public navbar "Поиск планов…" lookup. "ё" is folded to "е" on BOTH sides
   * (caller lowercases + replaces, SQL does the same on the stored value) so "ёжик" matches "ежик"
   * and vice versa; aliases seeded with either spelling are reachable.
   *
   * <p>Caller must already have lower-cased {@code qLower} AND replaced "ё"→"е"; passing a
   * normalized value keeps the SQL simpler and lets the {@code LOWER(name)} index (V33) assist when
   * the stored name contains no "ё" (prefix-match path).
   */
  @Query(
      "SELECT s FROM ServiceEntity s "
          + "WHERE s.isActive = true "
          + "AND ("
          + "REPLACE(LOWER(s.name), 'ё', 'е') LIKE CONCAT('%', :qLower, '%') "
          + "OR REPLACE(LOWER(COALESCE(s.searchAliases, '')), 'ё', 'е') "
          + "   LIKE CONCAT('%', :qLower, '%')"
          + ") "
          + "ORDER BY s.name ASC, s.id ASC")
  List<ServiceEntity> searchActiveByName(@Param("qLower") String qLower, Pageable pageable);
}
