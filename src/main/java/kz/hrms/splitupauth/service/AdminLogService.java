package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.AdminActionLogDto;
import kz.hrms.splitupauth.dto.RoomEventLogDto;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.RoomEventLogRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import jakarta.persistence.criteria.Predicate;
import kz.hrms.splitupauth.dto.AdminActionLogFilterRequest;
import kz.hrms.splitupauth.dto.PageResponse;
import kz.hrms.splitupauth.dto.RoomEventLogFilterRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
@Service
@RequiredArgsConstructor
public class AdminLogService {

    private final AdminActionLogRepository adminActionLogRepository;
    private final RoomEventLogRepository roomEventLogRepository;
    private final RoomRepository roomRepository;

    @Transactional(readOnly = true)
    public List<AdminActionLogDto> getAdminActionLogs(User currentUser) {
        ensureAdmin(currentUser);

        return adminActionLogRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(log -> AdminActionLogDto.builder()
                        .id(log.getId())
                        .eventId(log.getEventId() != null ? log.getEventId().toString() : null)
                        .adminUserId(log.getAdminUser() != null ? log.getAdminUser().getId() : null)
                        .actionType(log.getActionType().name())
                        .entityType(log.getEntityType())
                        .entityId(log.getEntityId())
                        .reason(log.getReason())
                        .ipAddress(log.getIpAddress())
                        .userAgent(log.getUserAgent())
                        .createdAt(log.getCreatedAt())
                        .build())
                .toList();
    }
    @Transactional(readOnly = true)
    public PageResponse<AdminActionLogDto> getAdminActionLogsPaged(
            User currentUser,
            AdminActionLogFilterRequest filter,
            int page,
            int size
    ) {
        ensureAdmin(currentUser);

        Pageable pageable = PageRequest.of(page, size);

        Specification<AdminActionLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new java.util.ArrayList<>();

            if (filter.getEntityType() != null && !filter.getEntityType().isBlank()) {
                predicates.add(cb.equal(root.get("entityType"), filter.getEntityType()));
            }
            if (filter.getActorUserId() != null) {
                predicates.add(cb.equal(root.get("adminUser").get("id"), filter.getActorUserId()));
            }
            if (filter.getDateFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.getDateFrom()));
            }
            if (filter.getDateTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), filter.getDateTo()));
            }

            query.orderBy(cb.desc(root.get("createdAt")));
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<AdminActionLog> result = adminActionLogRepository.findAll(spec, pageable);

        List<AdminActionLogDto> items = result.getContent().stream()
                .map(log -> AdminActionLogDto.builder()
                        .id(log.getId())
                        .eventId(log.getEventId() != null ? log.getEventId().toString() : null)
                        .adminUserId(log.getAdminUser() != null ? log.getAdminUser().getId() : null)
                        .actionType(log.getActionType().name())
                        .entityType(log.getEntityType())
                        .entityId(log.getEntityId())
                        .reason(log.getReason())
                        .ipAddress(log.getIpAddress())
                        .userAgent(log.getUserAgent())
                        .createdAt(log.getCreatedAt())
                        .build())
                .toList();

        return PageResponse.from(result, items);
    }
    @Transactional(readOnly = true)
    public List<RoomEventLogDto> getRoomEventLogs(User currentUser) {
        ensureAdmin(currentUser);

        return roomEventLogRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(log -> RoomEventLogDto.builder()
                        .id(log.getId())
                        .eventId(log.getEventId() != null ? log.getEventId().toString() : null)
                        .actorUserId(log.getActorUser() != null ? log.getActorUser().getId() : null)
                        .actorRole(log.getActorRole())
                        .roomId(log.getRoom() != null ? log.getRoom().getId() : null)
                        .roomMemberId(log.getRoomMember() != null ? log.getRoomMember().getId() : null)
                        .eventType(log.getEventType())
                        .oldState(log.getOldState())
                        .newState(log.getNewState())
                        .ipAddress(log.getIpAddress())
                        .userAgent(log.getUserAgent())
                        .createdAt(log.getCreatedAt())
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RoomEventLogDto> getRoomEventLogsByRoom(Long roomId, User currentUser) {
        ensureAdmin(currentUser);

        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

        return roomEventLogRepository.findByRoomOrderByCreatedAtDesc(room)
                .stream()
                .map(log -> RoomEventLogDto.builder()
                        .id(log.getId())
                        .eventId(log.getEventId() != null ? log.getEventId().toString() : null)
                        .actorUserId(log.getActorUser() != null ? log.getActorUser().getId() : null)
                        .actorRole(log.getActorRole())
                        .roomId(log.getRoom() != null ? log.getRoom().getId() : null)
                        .roomMemberId(log.getRoomMember() != null ? log.getRoomMember().getId() : null)
                        .eventType(log.getEventType())
                        .oldState(log.getOldState())
                        .newState(log.getNewState())
                        .ipAddress(log.getIpAddress())
                        .userAgent(log.getUserAgent())
                        .createdAt(log.getCreatedAt())
                        .build())
                .toList();
    }
    @Transactional(readOnly = true)
    public PageResponse<RoomEventLogDto> getRoomEventLogsPaged(
            User currentUser,
            RoomEventLogFilterRequest filter,
            int page,
            int size
    ) {
        ensureAdmin(currentUser);

        Pageable pageable = PageRequest.of(page, size);

        Specification<RoomEventLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new java.util.ArrayList<>();

            if (filter.getRoomId() != null) {
                predicates.add(cb.equal(root.get("room").get("id"), filter.getRoomId()));
            }
            if (filter.getActorUserId() != null) {
                predicates.add(cb.equal(root.get("actorUser").get("id"), filter.getActorUserId()));
            }
            if (filter.getEventType() != null && !filter.getEventType().isBlank()) {
                predicates.add(cb.equal(root.get("eventType"), filter.getEventType()));
            }
            if (filter.getDateFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.getDateFrom()));
            }
            if (filter.getDateTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), filter.getDateTo()));
            }

            query.orderBy(cb.desc(root.get("createdAt")));
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<RoomEventLog> result = roomEventLogRepository.findAll(spec, pageable);

        List<RoomEventLogDto> items = result.getContent().stream()
                .map(log -> RoomEventLogDto.builder()
                        .id(log.getId())
                        .eventId(log.getEventId() != null ? log.getEventId().toString() : null)
                        .actorUserId(log.getActorUser() != null ? log.getActorUser().getId() : null)
                        .actorRole(log.getActorRole())
                        .roomId(log.getRoom() != null ? log.getRoom().getId() : null)
                        .roomMemberId(log.getRoomMember() != null ? log.getRoomMember().getId() : null)
                        .eventType(log.getEventType())
                        .oldState(log.getOldState())
                        .newState(log.getNewState())
                        .ipAddress(log.getIpAddress())
                        .userAgent(log.getUserAgent())
                        .createdAt(log.getCreatedAt())
                        .build())
                .toList();

        return PageResponse.from(result, items);
    }
    private void ensureAdmin(User currentUser) {
        if (currentUser == null || currentUser.getRole() != Role.ADMIN) {
            throw new ForbiddenOperationException("Admin access required");
        }
    }
}