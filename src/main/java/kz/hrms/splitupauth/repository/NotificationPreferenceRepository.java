package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.NotificationPreference;
import kz.hrms.splitupauth.entity.NotificationType;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Long> {

    List<NotificationPreference> findByUser(User user);

    Optional<NotificationPreference> findByUserAndType(User user, NotificationType type);
}
