package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoomMemberRepository extends JpaRepository<RoomMember, Long> {
    Optional<RoomMember> findByIdAndRoomAndDeletedAtIsNull(Long id, Room room);
    Optional<RoomMember> findByRoomAndUserAndDeletedAtIsNull(Room room, User user);
    List<RoomMember> findByStatusAndDeletedAtIsNull(MemberStatus status);
    List<RoomMember> findByRoomAndDeletedAtIsNullOrderByCreatedAtAsc(Room room);
    Page<RoomMember> findByRoomAndDeletedAtIsNullOrderByCreatedAtAsc(Room room, Pageable pageable);
    long countByRoomAndStatusInAndDeletedAtIsNull(Room room, List<MemberStatus> statuses);
}