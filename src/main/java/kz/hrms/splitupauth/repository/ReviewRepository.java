package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.Review;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long> {

    List<Review> findByRecipientAndHiddenByAdminFalseOrderByCreatedAtDesc(User recipient);

    Optional<Review> findByAuthorAndRecipientAndRoom_Id(User author, User recipient, Long roomId);

    long countByRecipientAndHiddenByAdminFalse(User recipient);
}
