package kz.hrms.splitupauth.repository;

import java.util.Collection;
import java.util.List;
import kz.hrms.splitupauth.entity.TariffPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TariffPlanRepository extends JpaRepository<TariffPlan, Long> {
  List<TariffPlan> findByServiceIdAndIsActiveTrueOrderByIdAsc(Long serviceId);

  List<TariffPlan> findByServiceIdOrderByIdAsc(Long serviceId);

  List<TariffPlan> findByServiceIdInAndIsActiveTrue(Collection<Long> serviceIds);

  long countByServiceIdAndIsActiveTrue(Long serviceId);

  long countByServiceId(Long serviceId);
}
