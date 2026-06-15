package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.Feedback;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface FeedbackRepository extends JpaRepository<Feedback, Long>, JpaSpecificationExecutor<Feedback> {

    Page<Feedback> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);

    long countByUserAndCreatedAtAfter(User user, java.time.LocalDateTime threshold);
}
