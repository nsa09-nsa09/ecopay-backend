package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.SupportTicket;
import kz.hrms.splitupauth.entity.SupportTicketStatus;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {

    List<SupportTicket> findByUserOrderByCreatedAtDesc(User user);

    Optional<SupportTicket> findByIdAndUser(Long id, User user);

    boolean existsByRoomMemberAndTopicAndStatusIn(
            RoomMember roomMember,
            String topic,
            List<SupportTicketStatus> statuses
    );

    boolean existsByRoomMemberAndStatusIn(
            RoomMember roomMember,
            List<SupportTicketStatus> statuses
    );

    Optional<SupportTicket> findByIdAndRoomMemberAndStatusIn(
            Long id,
            RoomMember roomMember,
            List<SupportTicketStatus> statuses
    );

    List<SupportTicket> findByStatusInOrderByCreatedAtAsc(List<SupportTicketStatus> statuses);

    List<SupportTicket> findByAssignedAdminOrderByUpdatedAtDescCreatedAtDesc(User assignedAdmin);
    Page<SupportTicket> findByStatusInOrderByCreatedAtAsc(List<SupportTicketStatus> statuses, Pageable pageable);

    Page<SupportTicket> findByAssignedAdminOrderByUpdatedAtDescCreatedAtDesc(User assignedAdmin, Pageable pageable);
}