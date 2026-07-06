package kz.hrms.splitupauth.repository;

import java.util.List;
import kz.hrms.splitupauth.entity.SupportMessage;
import kz.hrms.splitupauth.entity.SupportTicket;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportMessageRepository extends JpaRepository<SupportMessage, Long> {
  List<SupportMessage> findByTicketOrderByCreatedAtAsc(SupportTicket ticket);
}
