package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.PayoutCardBinding;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PayoutCardBindingRepository extends JpaRepository<PayoutCardBinding, Long> {
    Optional<PayoutCardBinding> findByIdAndUser(Long id, User user);
}
