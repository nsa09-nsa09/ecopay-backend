package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.ServiceEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

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
     * Case-insensitive substring match on service name, scoped to active
     * services. Backs the public navbar "Поиск планов…" lookup; the
     * {@code LOWER(name)} index (V33) covers the prefix-match path.
     *
     * <p>Caller must already have lower-cased {@code qLower}; passing a
     * pre-lowered value keeps the SQL simpler and means the index is used
     * for prefix patterns ({@code foo%}).</p>
     */
    @Query("SELECT s FROM ServiceEntity s "
            + "WHERE s.isActive = true "
            + "AND LOWER(s.name) LIKE CONCAT('%', :qLower, '%') "
            + "ORDER BY s.name ASC, s.id ASC")
    List<ServiceEntity> searchActiveByName(@Param("qLower") String qLower, Pageable pageable);
}
