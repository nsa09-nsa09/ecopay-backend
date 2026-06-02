package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.RoomEventLog;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.repository.RoomEventLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only audit trail for critical room lifecycle events
 * (room_created, member_joined, payment_success, owner_access_granted,
 * member_confirmed, room_completed, ...). Satisfies the DoD requirement
 * "Audit logs on all critical operations". Writes to room_event_log, which is
 * enforced append-only at the DB role level (see migration V8).
 *
 * <p>Logging never breaks the business transaction: failures are swallowed and
 * logged, so an audit hiccup cannot roll back a payment or membership change.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RoomEventLogger {

    private final RoomEventLogRepository roomEventLogRepository;

    public void log(Room room, RoomMember member, User actor, String actorRole,
                    String eventType, Map<String, Object> payload) {
        try {
            ObjectNode newState = JsonNodeFactory.instance.objectNode();
            if (payload != null) {
                payload.forEach((k, v) -> newState.put(k, v == null ? null : String.valueOf(v)));
            }
            roomEventLogRepository.save(
                    RoomEventLog.builder()
                            .eventId(UUID.randomUUID())
                            .actorUser(actor)
                            .actorRole(actorRole)
                            .room(room)
                            .roomMember(member)
                            .eventType(eventType)
                            .newState(newState)
                            .createdAt(LocalDateTime.now())
                            .build()
            );
        } catch (Exception ex) {
            log.warn("Failed to write room event {} for room {}: {}",
                    eventType, room == null ? null : room.getId(), ex.getMessage());
        }
    }
}
