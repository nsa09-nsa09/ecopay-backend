package kz.hrms.splitupauth.repository;


import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomEventLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface RoomEventLogRepository extends JpaRepository<RoomEventLog, Long>, JpaSpecificationExecutor<RoomEventLog> {

    List<RoomEventLog> findAllByOrderByCreatedAtDesc();

    List<RoomEventLog> findByRoomOrderByCreatedAtDesc(Room room);
}