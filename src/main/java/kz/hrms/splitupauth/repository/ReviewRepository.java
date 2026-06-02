package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.Review;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long> {

    List<Review> findByRecipientAndHiddenByAdminFalseOrderByCreatedAtDesc(User recipient);

    Optional<Review> findByAuthorAndRecipientAndRoom_Id(User author, User recipient, Long roomId);

    long countByRecipientAndHiddenByAdminFalse(User recipient);

    /** Batch-aggregate visible review stats for a set of recipients (avoids N+1 in listings). */
    @Query("""
            select r.recipient.id as recipientId,
                   avg(r.rating)  as avgRating,
                   count(r)       as reviewCount
            from Review r
            where r.hiddenByAdmin = false
              and r.recipient.id in :recipientIds
            group by r.recipient.id
            """)
    List<OwnerRatingProjection> aggregateRatingByRecipientIds(@Param("recipientIds") Collection<Long> recipientIds);
}
