package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.TariffPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TariffPlanRepository extends JpaRepository<TariffPlan, Long> {
    List<TariffPlan> findByServiceIdAndIsActiveTrueOrderByIdAsc(Long serviceId);
}