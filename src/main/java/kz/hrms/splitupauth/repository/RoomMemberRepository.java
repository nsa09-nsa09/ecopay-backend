package kz.hrms.splitupauth.repository;

import jakarta.persistence.LockModeType;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RoomMemberRepository extends JpaRepository<RoomMember, Long> {
    Optional<RoomMember> findByIdAndRoomAndDeletedAtIsNull(Long id, Room room);
    Optional<RoomMember> findByRoomAndUserAndDeletedAtIsNull(Room room, User user);
    List<RoomMember> findByUserAndDeletedAtIsNullOrderByCreatedAtDesc(User user);
    List<RoomMember> findByStatusAndDeletedAtIsNull(MemberStatus status);
    List<RoomMember> findByRoomAndDeletedAtIsNullOrderByCreatedAtAsc(Room room);
    Page<RoomMember> findByRoomAndDeletedAtIsNullOrderByCreatedAtAsc(Room room, Pageable pageable);
    long countByRoomAndStatusInAndDeletedAtIsNull(Room room, List<MemberStatus> statuses);
    long countByUserAndDeletedAtIsNull(User user);
    Optional<RoomMember> findByRoomAndUserAndStatusIn(Room room, User user, List<MemberStatus> statuses);

    /** Room of a membership, read without loading the entity (so a later locked read is fresh). */
    @Query("select m.room.id from RoomMember m where m.id = :id")
    Optional<Long> findRoomIdById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from RoomMember m where m.id = :id")
    Optional<RoomMember> findWithLockById(@Param("id") Long id);

    /** Batch occupied-seat counts for a set of rooms (avoids N+1 in listings). */
    @Query("""
            select m.room.id as roomId, count(m) as occupied
            from RoomMember m
            where m.deletedAt is null
              and m.status in :statuses
              and m.room.id in :roomIds
            group by m.room.id
            """)
    List<RoomOccupancyProjection> countOccupiedByRoomIds(@Param("roomIds") Collection<Long> roomIds,
                                                         @Param("statuses") Collection<MemberStatus> statuses);
}