package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomChatMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoomChatMessageRepository extends JpaRepository<RoomChatMessage, Long> {

  /** Newest-first page of a room's chat history. The UI reverses it for display. */
  Page<RoomChatMessage> findByRoomOrderByCreatedAtDesc(Room room, Pageable pageable);
}
