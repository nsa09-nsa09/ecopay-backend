package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.ModerationQueue;
import kz.hrms.splitupauth.entity.ModerationQueueStatus;
import kz.hrms.splitupauth.entity.RoomMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModerationQueueRepository extends JpaRepository<ModerationQueue, Long> {

    List<ModerationQueue> findByStatusOrderByCreatedAtAsc(ModerationQueueStatus status);

    Optional<ModerationQueue> findByIdAndStatus(Long id, ModerationQueueStatus status);

    Optional<ModerationQueue> findByIdAndRoomMemberAndStatusIn(
            Long id,
            RoomMember roomMember,
            List<ModerationQueueStatus> statuses
    );

    boolean existsByRoomMemberAndStatusIn(RoomMember roomMember, List<ModerationQueueStatus> statuses);
}
