package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.StaffTwoFactorChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface StaffTwoFactorChallengeRepository
        extends JpaRepository<StaffTwoFactorChallenge, String> {

    Optional<StaffTwoFactorChallenge> findById(String id);

    @Modifying
    @Query("delete from StaffTwoFactorChallenge c where c.expiresAt < :before")
    int deleteByExpiresAtBefore(@Param("before") LocalDateTime before);
}
