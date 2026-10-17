package kz.hrms.splitupauth.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.RefundRequest;
import kz.hrms.splitupauth.entity.RefundRequestStatus;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRequestRepository extends JpaRepository<RefundRequest, Long> {

  Optional<RefundRequest> findByIdempotencyKey(String idempotencyKey);

  List<RefundRequest> findByRequesterOrderByCreatedAtDesc(User requester);

  List<RefundRequest> findByStatusOrderByCreatedAtAsc(RefundRequestStatus status);

  Page<RefundRequest> findByStatus(RefundRequestStatus status, Pageable pageable);

  List<RefundRequest> findAllByOrderByCreatedAtDesc();

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from RefundRequest r where r.id = :id")
  Optional<RefundRequest> findWithLockById(@Param("id") Long id);

  /**
   * C2: latest in-flight claim (REQUESTED or UNDER_REVIEW) filed by {@code requester} against a
   * CHARGE belonging to {@code member}. Used to tell the member's dashboard "you already have a
   * pending claim".
   */
  @Query(
      "select r from RefundRequest r "
          + "where r.requester = :requester "
          + "and r.paymentTransaction.roomMember = :member "
          + "and r.status in (kz.hrms.splitupauth.entity.RefundRequestStatus.REQUESTED, "
          + "                 kz.hrms.splitupauth.entity.RefundRequestStatus.UNDER_REVIEW) "
          + "order by r.createdAt desc")
  List<RefundRequest> findOpenForMember(
      @Param("requester") User requester, @Param("member") RoomMember member);

  /**
   * Variant for callers that only have the intent (same semantics as {@link #findOpenForMember}).
   */
  @Query(
      "select r from RefundRequest r "
          + "where r.requester = :requester "
          + "and r.paymentTransaction.paymentIntent = :intent "
          + "and r.status in (kz.hrms.splitupauth.entity.RefundRequestStatus.REQUESTED, "
          + "                 kz.hrms.splitupauth.entity.RefundRequestStatus.UNDER_REVIEW) "
          + "order by r.createdAt desc")
  List<RefundRequest> findOpenForIntent(
      @Param("requester") User requester, @Param("intent") PaymentIntent intent);
}
