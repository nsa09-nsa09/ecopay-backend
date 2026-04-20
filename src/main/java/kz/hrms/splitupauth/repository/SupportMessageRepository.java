package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.SupportMessage;
import kz.hrms.splitupauth.entity.SupportTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupportMessageRepository extends JpaRepository<SupportMessage, Long> {
    List<SupportMessage> findByTicketOrderByCreatedAtAsc(SupportTicket ticket);
}