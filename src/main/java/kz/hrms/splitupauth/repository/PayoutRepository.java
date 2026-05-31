package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.Payout;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PayoutRepository extends JpaRepository<Payout, Long> {
    List<Payout> findByUserOrderByCreatedAtDesc(User user);

    List<Payout> findByStatusInOrderByCreatedAtAsc(List<String> statuses);
}
