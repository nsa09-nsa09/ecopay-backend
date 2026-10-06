package kz.hrms.splitupauth.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.RefundRequest;
import kz.hrms.splitupauth.entity.RefundRequestStatus;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRequestRepository extends JpaRepository<RefundRequest, Long> {

  Optional<RefundRequest> findByIdempotencyKey(String idempotencyKey);

  List<RefundRequest> findByRequesterOrderByCreatedAtDesc(User requester);

  List<RefundRequest> findByStatusOrderByCreatedAtAsc(RefundRequestStatus status);

  List<RefundRequest> findAllByOrderByCreatedAtDesc();

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from RefundRequest r where r.id = :id")
  Optional<RefundRequest> findWithLockById(@Param("id") Long id);
}
