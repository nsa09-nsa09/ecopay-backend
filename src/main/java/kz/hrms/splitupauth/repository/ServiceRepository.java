package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.ServiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
