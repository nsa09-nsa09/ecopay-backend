package kz.hrms.splitupauth.websocket;

import kz.hrms.splitupauth.dto.SupportTicketResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SupportTicketRealtimeService {

    private static final String TICKET_TOPIC_PREFIX = "/topic/support-tickets/";
    private static final String STAFF_QUEUE_TOPIC = "/topic/staff/support-queue";

    private final SimpMessagingTemplate messagingTemplate;

    public void publishTicketUpdate(SupportTicketResponse ticket) {
        if (ticket == null || ticket.getId() == null) {
            return;
        }

        messagingTemplate.convertAndSend(TICKET_TOPIC_PREFIX + ticket.getId(), ticket);
        messagingTemplate.convertAndSend(STAFF_QUEUE_TOPIC, ticket);
    }
}
