package kz.hrms.splitupauth.service;

import java.util.List;
import java.util.Map;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.dto.RoomChatMessageDto;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.NotificationType;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomChatMessage;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.RoomChatMessageRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.util.TextSanitizer;
import kz.hrms.splitupauth.websocket.RoomChatRealtimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Room chat that opens once guests have paid. Participants are the room owner plus any member whose
 * status is {@link MemberStatus#PENDING} or {@link MemberStatus#ACTIVE} — i.e. payment SUCCESS has
 * moved them past APPLIED. Access is decided in the service layer via {@link #assertCanAccess}; the
 * WebSocket subscribe interceptor enforces the same rule inline (leaf repositories only, to avoid a
 * startup cycle with the message broker).
 */
@Service
@RequiredArgsConstructor
public class RoomChatService {

  /** Statuses that count as "has paid" and therefore grant chat access. */
  private static final List<MemberStatus> PAID_STATUSES =
      List.of(MemberStatus.PENDING, MemberStatus.ACTIVE);

  private final RoomRepository roomRepository;
  private final RoomMemberRepository roomMemberRepository;
  private final RoomChatMessageRepository chatMessageRepository;
  private final RoomChatRealtimeService realtimeService;
  private final NotificationService notificationService;

  /** Newest-first page of a room's chat history, for the caller if they are a participant. */
  @Transactional(readOnly = true)
  public PagedResponse<RoomChatMessageDto> history(User currentUser, Long roomId, int page, int size) {
    Room room = loadRoom(roomId);
    assertCanAccess(currentUser, room);

    int safeSize = Math.min(Math.max(size, 1), 100);
    Page<RoomChatMessage> result =
        chatMessageRepository.findByRoomOrderByCreatedAtDesc(
            room, PageRequest.of(Math.max(page, 0), safeSize));

    List<RoomChatMessageDto> items = result.map(RoomChatMessageDto::from).getContent();
    return PagedResponse.<RoomChatMessageDto>builder()
        .items(items)
        .page(result.getNumber())
        .size(result.getSize())
        .totalItems(result.getTotalElements())
        .totalPages(result.getTotalPages())
        .hasNext(result.hasNext())
        .hasPrevious(result.hasPrevious())
        .build();
  }

  /** Persist a message from a participant, then broadcast it to the room's chat topic. */
  @Transactional
  public RoomChatMessageDto send(User currentUser, Long roomId, String rawBody) {
    Room room = loadRoom(roomId);
    assertCanAccess(currentUser, room);

    String body = TextSanitizer.sanitize(rawBody);
    if (body == null || body.isBlank()) {
      throw new ForbiddenOperationException("Message is empty");
    }

    RoomChatMessage saved =
        chatMessageRepository.save(
            RoomChatMessage.builder().room(room).senderUser(currentUser).body(body).build());

    RoomChatMessageDto dto = RoomChatMessageDto.from(saved);
    realtimeService.broadcast(roomId, dto);
    notifyOtherParticipants(room, currentUser, dto);
    return dto;
  }

  /**
   * In-app notification of a new chat message to every other participant (owner + PENDING/ACTIVE
   * members), so it reaches them even when the room page isn't open. The live STOMP broadcast
   * handles anyone currently viewing; this is the persistent bell entry. In-app only.
   */
  private void notifyOtherParticipants(Room room, User sender, RoomChatMessageDto dto) {
    String title = "Новое сообщение";
    String body = dto.getSenderName() + " написал(а) в чате комнаты «" + room.getTitle() + "».";
    Map<String, Object> metadata = Map.of("roomId", room.getId());

    User owner = room.getOwner();
    if (owner != null && owner.getId() != null && !owner.getId().equals(sender.getId())) {
      notificationService.notify(
          owner, NotificationType.CHAT_MESSAGE, title, body, "/rooms/owner/" + room.getId(), metadata);
    }

    for (RoomMember member :
        roomMemberRepository.findByRoomAndDeletedAtIsNullOrderByCreatedAtAsc(room)) {
      if (member.getStatus() != MemberStatus.PENDING && member.getStatus() != MemberStatus.ACTIVE) {
        continue;
      }
      User u = member.getUser();
      if (u == null || u.getId() == null || u.getId().equals(sender.getId())) {
        continue;
      }
      notificationService.notify(
          u, NotificationType.CHAT_MESSAGE, title, body, "/rooms/member/" + room.getId(), metadata);
    }
  }

  private Room loadRoom(Long roomId) {
    return roomRepository
        .findById(roomId)
        .filter(r -> r.getDeletedAt() == null)
        .orElseThrow(() -> new ResourceNotFoundException("Room not found"));
  }

  private void assertCanAccess(User user, Room room) {
    if (!isParticipant(user, room)) {
      throw new ForbiddenOperationException("You are not a participant of this room's chat");
    }
  }

  private boolean isParticipant(User user, Room room) {
    if (room.getOwner() != null
        && user.getId() != null
        && user.getId().equals(room.getOwner().getId())) {
      return true;
    }
    return roomMemberRepository
        .findByRoomAndUserAndStatusIn(room, user, PAID_STATUSES)
        .filter(m -> m.getDeletedAt() == null)
        .isPresent();
  }
}
