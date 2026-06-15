package kz.hrms.splitupauth.repository;


import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomEventLog;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface RoomEventLogRepository extends JpaRepository<RoomEventLog, Long>, JpaSpecificationExecutor<RoomEventLog> {

    List<RoomEventLog> findAllByOrderByCreatedAtDesc();

    List<RoomEventLog> findByRoomOrderByCreatedAtDesc(Room room);

    /** Recent activity for a user as actor — bounded via Pageable for the member dashboard. */
    List<RoomEventLog> findByActorUserOrderByCreatedAtDesc(User actorUser, Pageable pageable);
}