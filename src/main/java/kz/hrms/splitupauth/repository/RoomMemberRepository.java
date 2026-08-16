package kz.hrms.splitupauth.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RoomMemberRepository extends JpaRepository<RoomMember, Long> {
  Optional<RoomMember> findByIdAndRoomAndDeletedAtIsNull(Long id, Room room);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select m from RoomMember m where m.id = :id")
  Optional<RoomMember> findWithLockById(@Param("id") Long id);

  Optional<RoomMember> findByRoomAndUserAndDeletedAtIsNull(Room room, User user);

  List<RoomMember> findByUserAndDeletedAtIsNullOrderByCreatedAtDesc(User user);

  List<RoomMember> findByStatusAndDeletedAtIsNull(MemberStatus status);

  List<RoomMember> findByRoomAndDeletedAtIsNullOrderByCreatedAtAsc(Room room);

  Page<RoomMember> findByRoomAndDeletedAtIsNullOrderByCreatedAtAsc(Room room, Pageable pageable);

  long countByRoomAndStatusInAndDeletedAtIsNull(Room room, List<MemberStatus> statuses);

  long countByUserAndDeletedAtIsNull(User user);

  long countByUserAndDeletedAtIsNullAndStatusIn(User user, List<MemberStatus> statuses);

  boolean existsByUser_IdAndDeletedAtIsNullAndStatusIn(Long userId, List<MemberStatus> statuses);

  Optional<RoomMember> findByRoomAndUserAndStatusIn(
      Room room, User user, List<MemberStatus> statuses);

  /** Successful participations: rooms the user was ACTIVE in that have since COMPLETED. */
  @Query(
      """
            select count(m)
            from RoomMember m
            where m.deletedAt is null
              and m.user = :user
              and m.status = kz.hrms.splitupauth.entity.MemberStatus.ACTIVE
              and m.room.status = kz.hrms.splitupauth.entity.RoomStatus.COMPLETED
            """)
  long countCompletedAsActiveMember(@Param("user") User user);

  /** Batch occupied-seat counts for a set of rooms (avoids N+1 in listings). */
  @Query(
      """
            select m.room.id as roomId, count(m) as occupied
            from RoomMember m
            where m.deletedAt is null
              and m.status in :statuses
              and m.room.id in :roomIds
            group by m.room.id
            """)
  List<RoomOccupancyProjection> countOccupiedByRoomIds(
      @Param("roomIds") Collection<Long> roomIds,
      @Param("statuses") Collection<MemberStatus> statuses);
}
