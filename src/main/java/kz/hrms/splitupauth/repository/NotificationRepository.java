package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.Notification;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);

    long countByUserAndReadAtIsNull(User user);

    /**
     * Bulk mark-all-read. Returns affected row count. {@code @Modifying} bypasses
     * the persistence context, so callers must not rely on entity state after.
     */
    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.user = :user AND n.readAt IS NULL")
    int markAllRead(@Param("user") User user, @Param("now") LocalDateTime now);
}
