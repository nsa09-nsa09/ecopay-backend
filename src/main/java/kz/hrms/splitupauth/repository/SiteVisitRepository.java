package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.SiteVisit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SiteVisitRepository extends JpaRepository<SiteVisit, Long> {
    Optional<SiteVisit> findByVisitorIdAndVisitDate(UUID visitorId, LocalDate visitDate);
}
