package kz.hrms.splitupauth.repository;

import java.util.List;
import kz.hrms.splitupauth.entity.PriceSnapshot;
import kz.hrms.splitupauth.entity.PriceWatchProvider;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PriceSnapshotRepository extends JpaRepository<PriceSnapshot, Long> {

  List<PriceSnapshot> findByProviderOrderByCapturedAtDesc(PriceWatchProvider provider, Pageable p);
}
