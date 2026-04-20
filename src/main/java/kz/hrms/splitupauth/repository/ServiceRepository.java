package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.ServiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ServiceRepository extends JpaRepository<ServiceEntity, Long> {
    List<ServiceEntity> findByIsActiveTrueOrderByIdAsc();
    List<ServiceEntity> findByCategoryIdAndIsActiveTrueOrderByIdAsc(Long categoryId);
}