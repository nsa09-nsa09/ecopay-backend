package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.LoginAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, Long> {

    /** Counted in SQL (served by idx_email_attempt) instead of loading every attempt row. */
    @Query("""
            select count(a) from LoginAttempt a
            where a.email = :email and a.attemptTime > :after and a.successful = false
            """)
    long countFailedSince(@Param("email") String email, @Param("after") LocalDateTime after);

    /** One set-based DELETE; a derived deleteBy… would load and delete row by row. */
    @Modifying
    @Query("delete from LoginAttempt a where a.attemptTime < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
