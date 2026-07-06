package kz.hrms.splitupauth.repository;

import java.util.List;
import kz.hrms.splitupauth.entity.PriceChange;
import kz.hrms.splitupauth.entity.PriceWatchProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PriceChangeRepository extends JpaRepository<PriceChange, Long> {

  List<PriceChange> findAllByOrderByChangedAtDesc();

  List<PriceChange> findByAcknowledgedFalseOrderByChangedAtDesc();

  List<PriceChange> findByProviderOrderByChangedAtDesc(PriceWatchProvider provider);
}
