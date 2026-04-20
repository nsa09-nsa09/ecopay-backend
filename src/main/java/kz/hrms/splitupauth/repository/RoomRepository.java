package kz.hrms.splitupauth.repository;

import jakarta.persistence.LockModeType;
import kz.hrms.splitupauth.entity.Room;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.RoomType;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RoomRepository extends JpaRepository<Room, Long> {
    List<Room> findByDeletedAtIsNullOrderByCreatedAtDesc();
    Page<Room> findByDeletedAtIsNull(Pageable pageable);

    Page<Room> findByDeletedAtIsNullAndStatus(RoomStatus status, Pageable pageable);

    Page<Room> findByDeletedAtIsNullAndCategory_Id(Long categoryId, Pageable pageable);

    Page<Room> findByDeletedAtIsNullAndRoomType(RoomType roomType, Pageable pageable);

    Page<Room> findByDeletedAtIsNullAndStatusAndRoomType(RoomStatus status, RoomType roomType, Pageable pageable);

    Page<Room> findByDeletedAtIsNullAndStatusAndCategory_Id(RoomStatus status, Long categoryId, Pageable pageable);

    Page<Room> findByDeletedAtIsNullAndRoomTypeAndCategory_Id(RoomType roomType, Long categoryId, Pageable pageable);

    Page<Room> findByDeletedAtIsNullAndStatusAndRoomTypeAndCategory_Id(
            RoomStatus status,
            RoomType roomType,
            Long categoryId,
            Pageable pageable
    );
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
       select r
       from Room r
       where r.id = :id
         and r.deletedAt is null
       """)
    Optional<Room> findByIdForUpdate(@Param("id") Long id);
    List<Room> findByOwnerAndDeletedAtIsNullOrderByCreatedAtDesc(User owner);
    List<Room> findByStatusAndDeletedAtIsNullOrderByCreatedAtDesc(RoomStatus status);
    List<Room> findByStatusAndDeletedAtIsNullAndStartDateLessThanEqual(RoomStatus status, LocalDateTime startDate);
}
