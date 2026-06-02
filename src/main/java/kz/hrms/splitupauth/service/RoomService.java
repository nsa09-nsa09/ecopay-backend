package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.hrms.splitupauth.dto.CreateRoomRequest;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.dto.RoomResponse;
import kz.hrms.splitupauth.dto.RoomSummaryDto;
import kz.hrms.splitupauth.dto.UpdateRoomRequest;
import kz.hrms.splitupauth.entity.AccessType;
import kz.hrms.splitupauth.entity.Category;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.RoomType;
import kz.hrms.splitupauth.entity.ServiceEntity;
import kz.hrms.splitupauth.entity.TariffPlan;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.VerificationMode;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.CategoryRepository;
import kz.hrms.splitupauth.repository.OwnerRatingProjection;
import kz.hrms.splitupauth.repository.ReviewRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomOccupancyProjection;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.ServiceRepository;
import kz.hrms.splitupauth.repository.TariffPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoomService {

    private final RoomRepository roomRepository;
    private final CategoryRepository categoryRepository;
    private final ServiceRepository serviceRepository;
    private final TariffPlanRepository tariffPlanRepository;
    private final RoomMapper roomMapper;
    private final RoomEventLogger roomEventLogger;
    private final ObjectMapper objectMapper;
    private final ReviewRepository reviewRepository;
    private final RoomMemberRepository roomMemberRepository;

    /** Member statuses that occupy a seat (see CLAUDE.md). */
    private static final List<MemberStatus> OCCUPYING_STATUSES = List.of(MemberStatus.PENDING, MemberStatus.ACTIVE);

    private void ensureStatusTransition(RoomStatus currentStatus, RoomStatus targetStatus) {
        boolean allowed =
                (currentStatus == RoomStatus.OPEN && targetStatus == RoomStatus.IN_VERIFICATION)
                        || (currentStatus == RoomStatus.OPEN && targetStatus == RoomStatus.CANCELLED)
                        || (currentStatus == RoomStatus.IN_VERIFICATION && targetStatus == RoomStatus.CANCELLED)
                        || (currentStatus == RoomStatus.IN_VERIFICATION && targetStatus == RoomStatus.ACTIVE)
                        || (currentStatus == RoomStatus.ACTIVE && targetStatus == RoomStatus.COMPLETED)
                        || targetStatus == RoomStatus.BLOCKED;

        if (!allowed) {
            throw new InvalidRequestException(
                    "Invalid room status transition: " + currentStatus + " -> " + targetStatus
            );
        }
    }

    @Transactional
    public RoomResponse createRoom(User currentUser, CreateRoomRequest request) {
        if (currentUser.getPhoneVerifiedAt() == null) {
            throw new ForbiddenOperationException("Verify your phone number before creating a room");
        }

        Category category = null;
        if (request.getCategoryId() != null) {
            category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
        }

        ServiceEntity service = serviceRepository.findById(request.getServiceId())
                .filter(serviceEntity -> Boolean.TRUE.equals(serviceEntity.getIsActive()))
                .orElseThrow(() -> new ResourceNotFoundException("Service not found"));

        TariffPlan tariffPlan = null;
        if (request.getTariffPlanId() != null) {
            tariffPlan = tariffPlanRepository.findById(request.getTariffPlanId())
                    .filter(tp -> Boolean.TRUE.equals(tp.getIsActive()))
                    .orElseThrow(() -> new ResourceNotFoundException("Tariff plan not found"));

            if (!tariffPlan.getService().getId().equals(service.getId())) {
                throw new InvalidRequestException("Tariff plan does not belong to the selected service");
            }
        }

        validateCreateRequest(request);

        // Hybrid access/restrictions: request value wins, else inherit tariff defaults.
        JsonNode rules = tariffRules(tariffPlan);
        AccessType accessType = request.getAccessType() != null
                ? request.getAccessType()
                : enumRule(rules, "defaultAccessType");
        if (accessType == null) {
            throw new InvalidRequestException("Access type is required");
        }
        String regionRestriction = request.getRegionRestriction() != null
                ? request.getRegionRestriction()
                : textRule(rules, "region");
        Boolean requiresEmailForInvite = request.getRequiresEmailForInvite() != null
                ? request.getRequiresEmailForInvite()
                : boolRule(rules, "requiresEmailForInvite");
        Boolean emailChangeForbidden = request.getEmailChangeForbidden() != null
                ? request.getEmailChangeForbidden()
                : boolRule(rules, "emailChangeForbidden");
        Integer accessGrantSlaHours = request.getAccessGrantSlaHours() != null
                ? request.getAccessGrantSlaHours()
                : intRule(rules, "accessGrantSlaHours");
        // Surface the catalog sharing warning as the room's restriction note when none provided.
        String operatorRestrictions = request.getOperatorRestrictions() != null
                ? request.getOperatorRestrictions()
                : textRule(rules, "sharingWarning");

        Room room = Room.builder()
                .owner(currentUser)
                .category(category)
                .service(service)
                .tariffPlan(tariffPlan)
                .roomType(request.getRoomType())
                .verificationMode(VerificationMode.RISK_BASED)
                .status(RoomStatus.OPEN)
                .title(request.getTitle())
                .description(request.getDescription())
                .maxMembers(request.getMaxMembers())
                .priceTotal(request.getPriceTotal())
                .pricePerMember(request.getPricePerMember())
                .currency(request.getCurrency() != null ? request.getCurrency() : "KZT")
                .periodType(request.getPeriodType())
                .startDate(request.getStartDate())
                .cancellationPolicy(request.getCancellationPolicy())
                .providerName(request.getProviderName())
                .tariffNameSnapshot(request.getTariffNameSnapshot())
                .connectionType(request.getConnectionType())
                .operatorRestrictions(operatorRestrictions)
                .operatorTermsConfirmed(Boolean.TRUE.equals(request.getOperatorTermsConfirmed()))
                .accessType(accessType)
                .regionRestriction(regionRestriction)
                .requiresEmailForInvite(requiresEmailForInvite)
                .emailChangeForbidden(emailChangeForbidden)
                .accessGrantSlaHours(accessGrantSlaHours)
                .build();

        room = roomRepository.save(room);

        roomEventLogger.log(room, null, currentUser, "OWNER", "room_created",
                java.util.Map.of("roomType", String.valueOf(room.getRoomType()),
                        "maxMembers", room.getMaxMembers(),
                        "pricePerMember", String.valueOf(room.getPricePerMember())));

        return roomMapper.toResponse(room);
    }

    @Transactional(readOnly = true)
    public RoomResponse getRoom(Long roomId) {
        Room room = roomRepository.findById(roomId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

        RoomResponse response = roomMapper.toResponse(room);
        applyOwnerRating(response, room.getOwner().getId());
        int occupied = (int) roomMemberRepository.countByRoomAndStatusInAndDeletedAtIsNull(room, OCCUPYING_STATUSES);
        response.setFilledSeats(occupied);
        response.setFreeSeats(Math.max(0, response.getMaxMembers() - occupied));
        return response;
    }

    @Transactional(readOnly = true)
    public PagedResponse<RoomSummaryDto> getRooms(
            int page,
            int size,
            RoomStatus status,
            RoomType roomType,
            Long categoryId,
            Long serviceId,
            String sortBy,
            String sortDir
    ) {
        Pageable pageable = buildPageable(page, size, sortBy, sortDir);

        Specification<Room> spec = (root, q, cb) -> cb.isNull(root.get("deletedAt"));
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        if (roomType != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("roomType"), roomType));
        }
        if (categoryId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("category").get("id"), categoryId));
        }
        if (serviceId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("service").get("id"), serviceId));
        }

        Page<Room> resultPage = roomRepository.findAll(spec, pageable);
        return toPagedResponse(resultPage);
    }

    @Transactional(readOnly = true)
    public PagedResponse<RoomSummaryDto> getMyRooms(User user, int page, int size) {
        Pageable pageable = buildPageable(page, size, null, null);

        Specification<Room> spec = (root, q, cb) -> cb.and(
                cb.isNull(root.get("deletedAt")),
                cb.equal(root.get("owner"), user)
        );

        Page<Room> resultPage = roomRepository.findAll(spec, pageable);
        return toPagedResponse(resultPage);
    }

    private Pageable buildPageable(int page, int size, String sortBy, String sortDir) {
        String resolvedSortBy = (sortBy == null || sortBy.isBlank()) ? "createdAt" : sortBy;
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        if (page < 0) page = 0;
        if (size <= 0) size = 20;
        if (size > 100) size = 100;
        return PageRequest.of(page, size, Sort.by(direction, resolvedSortBy));
    }

    private PagedResponse<RoomSummaryDto> toPagedResponse(Page<Room> resultPage) {
        List<RoomSummaryDto> items = resultPage.getContent().stream().map(roomMapper::toSummary).toList();
        enrichOwnerRatings(items);
        enrichSeats(items);
        return PagedResponse.<RoomSummaryDto>builder()
                .items(items)
                .page(resultPage.getNumber())
                .size(resultPage.getSize())
                .totalItems(resultPage.getTotalElements())
                .totalPages(resultPage.getTotalPages())
                .hasNext(resultPage.hasNext())
                .hasPrevious(resultPage.hasPrevious())
                .build();
    }

    /** Batch-load owner review stats for a page of summaries (one query, no N+1). */
    private void enrichOwnerRatings(List<RoomSummaryDto> summaries) {
        if (summaries.isEmpty()) {
            return;
        }
        Set<Long> ownerIds = summaries.stream()
                .map(RoomSummaryDto::getOwnerUserId)
                .collect(Collectors.toSet());
        Map<Long, OwnerRatingProjection> byOwner = reviewRepository.aggregateRatingByRecipientIds(ownerIds).stream()
                .collect(Collectors.toMap(OwnerRatingProjection::getRecipientId, p -> p));
        for (RoomSummaryDto summary : summaries) {
            OwnerRatingProjection p = byOwner.get(summary.getOwnerUserId());
            summary.setOwnerRating(roundRating(p));
            summary.setOwnerReviewCount(p != null && p.getReviewCount() != null ? p.getReviewCount().intValue() : 0);
        }
    }

    /** Batch occupied-seat counts for a page of summaries (one query, no N+1). */
    private void enrichSeats(List<RoomSummaryDto> summaries) {
        if (summaries.isEmpty()) {
            return;
        }
        Set<Long> roomIds = summaries.stream()
                .map(RoomSummaryDto::getId)
                .collect(Collectors.toSet());
        Map<Long, Long> occupiedByRoom = roomMemberRepository.countOccupiedByRoomIds(roomIds, OCCUPYING_STATUSES).stream()
                .collect(Collectors.toMap(RoomOccupancyProjection::getRoomId, RoomOccupancyProjection::getOccupied));
        for (RoomSummaryDto summary : summaries) {
            int occupied = occupiedByRoom.getOrDefault(summary.getId(), 0L).intValue();
            int max = summary.getMaxMembers() != null ? summary.getMaxMembers() : 0;
            summary.setFilledSeats(occupied);
            summary.setFreeSeats(Math.max(0, max - occupied));
        }
    }

    private void applyOwnerRating(RoomResponse response, Long ownerId) {
        List<OwnerRatingProjection> rows = reviewRepository.aggregateRatingByRecipientIds(List.of(ownerId));
        OwnerRatingProjection p = rows.isEmpty() ? null : rows.get(0);
        response.setOwnerRating(roundRating(p));
        response.setOwnerReviewCount(p != null && p.getReviewCount() != null ? p.getReviewCount().intValue() : 0);
    }

    private Double roundRating(OwnerRatingProjection p) {
        if (p == null || p.getAvgRating() == null) {
            return null;
        }
        return Math.round(p.getAvgRating() * 10.0) / 10.0;
    }

    @Transactional
    public RoomResponse updateRoom(Long roomId, User currentUser, UpdateRoomRequest request) {
        Room room = roomRepository.findById(roomId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

        if (!room.getOwner().getId().equals(currentUser.getId())) {
            throw new ForbiddenOperationException("Only room owner can update the room");
        }

        if (room.getStatus() != RoomStatus.OPEN) {
            throw new InvalidRequestException("Only OPEN rooms can be updated");
        }

        if (!room.getStartDate().isAfter(LocalDateTime.now())) {
            throw new InvalidRequestException("Room cannot be updated after start date");
        }

        if (request.getTitle() != null) {
            room.setTitle(request.getTitle());
        }

        if (request.getDescription() != null) {
            room.setDescription(request.getDescription());
        }

        if (request.getMaxMembers() != null) {
            room.setMaxMembers(request.getMaxMembers());
        }

        if (request.getPriceTotal() != null) {
            room.setPriceTotal(request.getPriceTotal());
        }

        if (request.getPricePerMember() != null) {
            room.setPricePerMember(request.getPricePerMember());
        }

        if (request.getCancellationPolicy() != null) {
            room.setCancellationPolicy(request.getCancellationPolicy());
        }

        if (request.getProviderName() != null) {
            room.setProviderName(request.getProviderName());
        }

        if (request.getTariffNameSnapshot() != null) {
            room.setTariffNameSnapshot(request.getTariffNameSnapshot());
        }

        if (request.getConnectionType() != null) {
            room.setConnectionType(request.getConnectionType());
        }

        if (request.getOperatorRestrictions() != null) {
            room.setOperatorRestrictions(request.getOperatorRestrictions());
        }

        if (request.getOperatorTermsConfirmed() != null) {
            room.setOperatorTermsConfirmed(request.getOperatorTermsConfirmed());
        }

        if (request.getAccessType() != null) {
            room.setAccessType(request.getAccessType());
        }

        if (request.getRegionRestriction() != null) {
            room.setRegionRestriction(request.getRegionRestriction());
        }

        if (request.getRequiresEmailForInvite() != null) {
            room.setRequiresEmailForInvite(request.getRequiresEmailForInvite());
        }

        if (request.getEmailChangeForbidden() != null) {
            room.setEmailChangeForbidden(request.getEmailChangeForbidden());
        }

        if (request.getAccessGrantSlaHours() != null) {
            room.setAccessGrantSlaHours(request.getAccessGrantSlaHours());
        }

        validateExistingRoom(room);

        room = roomRepository.save(room);

        return roomMapper.toResponse(room);
    }

    @Transactional
    public RoomResponse markReadyForVerification(Long roomId, User currentUser) {
        Room room = roomRepository.findById(roomId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

        if (!room.getOwner().getId().equals(currentUser.getId())) {
            throw new ForbiddenOperationException("Only room owner can mark room ready for verification");
        }

        transitionRoomToVerification(room, LocalDateTime.now());

        room = roomRepository.save(room);

        return roomMapper.toResponse(room);
    }

    @Transactional
    public int moveStartedOpenRoomsToVerification() {
        LocalDateTime now = LocalDateTime.now();
        List<Room> roomsToTransition = roomRepository
                .findByStatusAndDeletedAtIsNullAndStartDateLessThanEqual(RoomStatus.OPEN, now)
                .stream()
                .filter(room -> !room.getStartDate().isAfter(now))
                .toList();

        if (roomsToTransition.isEmpty()) {
            return 0;
        }

        roomsToTransition.forEach(room -> transitionRoomToVerification(room, now));
        roomRepository.saveAll(roomsToTransition);

        return roomsToTransition.size();
    }


    private void validateCreateRequest(CreateRoomRequest request) {
        boolean hasAnyPositiveAmount =
                hasPositiveAmount(request.getPriceTotal()) || hasPositiveAmount(request.getPricePerMember());
        if (!hasAnyPositiveAmount) {
            throw new InvalidRequestException("Either positive priceTotal or positive pricePerMember must be provided");
        }

        if (request.getStartDate().isBefore(LocalDateTime.now())) {
            throw new InvalidRequestException("Start date must be in the future");
        }

        if (request.getRoomType() == RoomType.TELECOM) {
            if (request.getProviderName() == null || request.getProviderName().isBlank()) {
                throw new InvalidRequestException("Provider name is required for TELECOM room");
            }

            if (request.getConnectionType() == null) {
                throw new InvalidRequestException("Connection type is required for TELECOM room");
            }

            if (!Boolean.TRUE.equals(request.getOperatorTermsConfirmed())) {
                throw new InvalidRequestException("Operator terms must be confirmed for TELECOM room");
            }
        }
    }

    private void validateExistingRoom(Room room) {
        boolean hasAnyPositiveAmount =
                hasPositiveAmount(room.getPriceTotal()) || hasPositiveAmount(room.getPricePerMember());
        if (!hasAnyPositiveAmount) {
            throw new InvalidRequestException("Either positive priceTotal or positive pricePerMember must be provided");
        }

        if (room.getMaxMembers() == null || room.getMaxMembers() < 2) {
            throw new InvalidRequestException("Max members must be at least 2");
        }

        if (room.getRoomType() == RoomType.TELECOM) {
            if (room.getProviderName() == null || room.getProviderName().isBlank()) {
                throw new InvalidRequestException("Provider name is required for TELECOM room");
            }

            if (room.getConnectionType() == null) {
                throw new InvalidRequestException("Connection type is required for TELECOM room");
            }

            if (!Boolean.TRUE.equals(room.getOperatorTermsConfirmed())) {
                throw new InvalidRequestException("Operator terms must be confirmed for TELECOM room");
            }
        }
    }

    private boolean hasPositiveAmount(BigDecimal amount) {
        return amount != null && amount.signum() > 0;
    }

    /** Parse a tariff plan's operator_rules JSON; returns null if absent or malformed. */
    private JsonNode tariffRules(TariffPlan tariffPlan) {
        if (tariffPlan == null || tariffPlan.getOperatorRules() == null) {
            return null;
        }
        try {
            return objectMapper.readTree(tariffPlan.getOperatorRules());
        } catch (Exception e) {
            return null;
        }
    }

    private String textRule(JsonNode rules, String field) {
        if (rules == null || !rules.hasNonNull(field)) {
            return null;
        }
        return rules.get(field).asText();
    }

    private Boolean boolRule(JsonNode rules, String field) {
        if (rules == null || !rules.hasNonNull(field)) {
            return null;
        }
        return rules.get(field).asBoolean();
    }

    private Integer intRule(JsonNode rules, String field) {
        if (rules == null || !rules.hasNonNull(field)) {
            return null;
        }
        return rules.get(field).asInt();
    }

    private AccessType enumRule(JsonNode rules, String field) {
        String value = textRule(rules, field);
        if (value == null) {
            return null;
        }
        try {
            return AccessType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void transitionRoomToVerification(Room room, LocalDateTime transitionTime) {
        ensureStatusTransition(room.getStatus(), RoomStatus.IN_VERIFICATION);
        validateExistingRoom(room);

        room.setStatus(RoomStatus.IN_VERIFICATION);
        if (room.getReadyForVerificationAt() == null) {
            room.setReadyForVerificationAt(transitionTime);
        }
    }

    @Transactional
    public RoomResponse cancelRoom(Long roomId, User currentUser) {
        Room room = roomRepository.findById(roomId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

        if (!room.getOwner().getId().equals(currentUser.getId())) {
            throw new ForbiddenOperationException("Only room owner can cancel the room");
        }

        if (!room.getStartDate().isAfter(LocalDateTime.now())) {
            throw new InvalidRequestException("Room cannot be cancelled after start date");
        }

        if (!(room.getStatus() == RoomStatus.OPEN || room.getStatus() == RoomStatus.IN_VERIFICATION)) {
            throw new InvalidRequestException("Only OPEN or IN_VERIFICATION rooms can be cancelled");
        }

        ensureStatusTransition(room.getStatus(), RoomStatus.CANCELLED);

        room.setStatus(RoomStatus.CANCELLED);

        room = roomRepository.save(room);

        return roomMapper.toResponse(room);
    }

    @Transactional
    public RoomResponse completeRoom(Long roomId, User currentUser) {
        Room room = roomRepository.findById(roomId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

        if (!room.getOwner().getId().equals(currentUser.getId())) {
            throw new ForbiddenOperationException("Only room owner can complete the room");
        }

        ensureStatusTransition(room.getStatus(), RoomStatus.COMPLETED);

        room.setStatus(RoomStatus.COMPLETED);
        room.setCompletedAt(LocalDateTime.now());

        room = roomRepository.save(room);

        roomEventLogger.log(room, null, currentUser, "OWNER", "room_completed", java.util.Map.of());

        return roomMapper.toResponse(room);
    }

    @SuppressWarnings("unused")
    @Transactional
    public RoomResponse blockRoom(Long roomId, User currentUser, String reason) {
        Room room = roomRepository.findById(roomId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

        ensureStatusTransition(room.getStatus(), RoomStatus.BLOCKED);

        room.setStatus(RoomStatus.BLOCKED);
//        room.setBlockedReason(reason);
        room.setBlockedAt(LocalDateTime.now());

        room = roomRepository.save(room);

        return roomMapper.toResponse(room);
    }
}
