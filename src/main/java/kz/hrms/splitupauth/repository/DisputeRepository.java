package kz.hrms.splitupauth.repository;

import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.entity.Dispute;
import kz.hrms.splitupauth.entity.DisputeStatus;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.SupportTicket;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DisputeRepository extends JpaRepository<Dispute, Long> {

  List<Dispute> findByStatusInOrderByCreatedAtAsc(List<DisputeStatus> statuses);

  List<Dispute> findByOpenedByUserOrderByCreatedAtDesc(User user);

  Optional<Dispute> findByIdAndOpenedByUser(Long id, User user);

  boolean existsByRoomMemberAndStatusIn(RoomMember roomMember, List<DisputeStatus> statuses);

  @Query(
      """
            select count(d) > 0
            from Dispute d
            where d.room = :room
              and d.status in :statuses
            """)
  boolean existsByRoomAndStatusIn(
      @Param("room") Room room, @Param("statuses") List<DisputeStatus> statuses);

  Optional<Dispute> findByTicket(SupportTicket ticket);

  Optional<Dispute> findByIdAndRoomMemberAndStatusIn(
      Long id, RoomMember roomMember, List<DisputeStatus> statuses);

  Page<Dispute> findByStatusInOrderByCreatedAtAsc(List<DisputeStatus> statuses, Pageable pageable);

  long countByOpenedByUser(User user);

  @Query(
      """
            select count(d)
            from Dispute d
            where (d.openedByUser = :user or d.room.owner = :user)
              and d.status in :statuses
            """)
  long countOpenFinancialDisputesForUser(
      @Param("user") User user, @Param("statuses") List<DisputeStatus> statuses);

  /** Confirmed violations against a user as the room owner (resolved owner-fault disputes). */
  @Query(
      """
            select count(d)
            from Dispute d
            where d.room.owner = :owner
              and d.status = kz.hrms.splitupauth.entity.DisputeStatus.RESOLVED
              and d.decision = 'OWNER_VIOLATION_CONFIRMED'
            """)
  long countConfirmedViolationsAgainstOwner(@Param("owner") User owner);
}
