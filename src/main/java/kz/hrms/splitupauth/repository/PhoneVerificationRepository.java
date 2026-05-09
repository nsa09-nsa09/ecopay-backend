package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.PhoneVerification;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface PhoneVerificationRepository extends JpaRepository<PhoneVerification, Long> {

    Optional<PhoneVerification> findTopByUserAndPhoneAndVerifiedAtIsNullOrderByCreatedAtDesc(
            User user, String phone);

    long countByPhoneAndCreatedAtAfter(String phone, LocalDateTime since);

    Optional<PhoneVerification> findTopByPhoneOrderByCreatedAtDesc(String phone);
}
