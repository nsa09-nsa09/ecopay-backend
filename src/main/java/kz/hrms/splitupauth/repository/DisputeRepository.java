package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.Dispute;
import kz.hrms.splitupauth.entity.DisputeStatus;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.SupportTicket;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DisputeRepository extends JpaRepository<Dispute, Long> {

    List<Dispute> findByStatusInOrderByCreatedAtAsc(List<DisputeStatus> statuses);

    List<Dispute> findByOpenedByUserOrderByCreatedAtDesc(User user);

    Optional<Dispute> findByIdAndOpenedByUser(Long id, User user);

    boolean existsByRoomMemberAndStatusIn(RoomMember roomMember, List<DisputeStatus> statuses);

    Optional<Dispute> findByTicket(SupportTicket ticket);

    Optional<Dispute> findByIdAndRoomMemberAndStatusIn(
            Long id,
            RoomMember roomMember,
            List<DisputeStatus> statuses
    );

    Page<Dispute> findByStatusInOrderByCreatedAtAsc(List<DisputeStatus> statuses, Pageable pageable);
}