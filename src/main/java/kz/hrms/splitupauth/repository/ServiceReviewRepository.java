package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.ServiceReview;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ServiceReviewRepository extends JpaRepository<ServiceReview, Long> {

    Optional<ServiceReview> findByAuthor(User author);

    Optional<ServiceReview> findByAuthorId(Long authorId);

    boolean existsByAuthor(User author);

    List<ServiceReview> findTop30ByFeaturedTrueOrderByCreatedAtDesc();

    Page<ServiceReview> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<ServiceReview> findByFeaturedOrderByCreatedAtDesc(boolean featured, Pageable pageable);
}
