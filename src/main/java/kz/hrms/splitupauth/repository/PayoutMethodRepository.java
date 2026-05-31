package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.PayoutMethod;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PayoutMethodRepository extends JpaRepository<PayoutMethod, Long> {
    List<PayoutMethod> findByUserAndStatusOrderByIsDefaultDescCreatedAtDesc(User user, String status);

    Optional<PayoutMethod> findByUserAndIsDefaultTrueAndStatus(User user, String status);
}
