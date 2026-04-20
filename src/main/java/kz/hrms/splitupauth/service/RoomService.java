package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.CreateRoomRequest;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.dto.RoomResponse;
import kz.hrms.splitupauth.dto.RoomSummaryDto;
import kz.hrms.splitupauth.dto.UpdateRoomRequest;
import kz.hrms.splitupauth.entity.Category;
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
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.ServiceRepository;
import kz.hrms.splitupauth.repository.TariffPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RoomService {

    private final RoomRepository roomRepository;
    private final CategoryRepository categoryRepository;
    private final ServiceRepository serviceRepository;
    private final TariffPlanRepository tariffPlanRepository;
    private final RoomMapper roomMapper;

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
                .operatorRestrictions(request.getOperatorRestrictions())
                .operatorTermsConfirmed(Boolean.TRUE.equals(request.getOperatorTermsConfirmed()))
                .build();

        room = roomRepository.save(room);

        return roomMapper.toResponse(room);
    }

    @Transactional(readOnly = true)
    public RoomResponse getRoom(Long roomId) {
        Room room = roomRepository.findById(roomId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

        return roomMapper.toResponse(room);
    }

    @Transactional(readOnly = true)
    public PagedResponse<RoomSummaryDto> getRooms(
            int page,
            int size,
            RoomStatus status,
            RoomType roomType,
            Long categoryId,
            String sortBy,
            String sortDir
    ) {
        String resolvedSortBy = (sortBy == null || sortBy.isBlank()) ? "createdAt" : sortBy;
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;

        if (page < 0) {
            page = 0;
        }

        if (size <= 0) {
            size = 20;
        }

        if (size > 100) {
            size = 100;
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, resolvedSortBy));

        Page<Room> resultPage;

        if (status != null && roomType != null && categoryId != null) {
            resultPage = roomRepository.findByDeletedAtIsNullAndStatusAndRoomTypeAndCategory_Id(
                    status, roomType, categoryId, pageable
            );
        } else if (status != null && roomType != null) {
            resultPage = roomRepository.findByDeletedAtIsNullAndStatusAndRoomType(status, roomType, pageable);
        } else if (status != null && categoryId != null) {
            resultPage = roomRepository.findByDeletedAtIsNullAndStatusAndCategory_Id(status, categoryId, pageable);
        } else if (roomType != null && categoryId != null) {
            resultPage = roomRepository.findByDeletedAtIsNullAndRoomTypeAndCategory_Id(roomType, categoryId, pageable);
        } else if (status != null) {
            resultPage = roomRepository.findByDeletedAtIsNullAndStatus(status, pageable);
        } else if (roomType != null) {
            resultPage = roomRepository.findByDeletedAtIsNullAndRoomType(roomType, pageable);
        } else if (categoryId != null) {
            resultPage = roomRepository.findByDeletedAtIsNullAndCategory_Id(categoryId, pageable);
        } else {
            resultPage = roomRepository.findByDeletedAtIsNull(pageable);
        }

        return PagedResponse.<RoomSummaryDto>builder()
                .items(resultPage.getContent().stream().map(roomMapper::toSummary).toList())
                .page(resultPage.getNumber())
                .size(resultPage.getSize())
                .totalItems(resultPage.getTotalElements())
                .totalPages(resultPage.getTotalPages())
                .hasNext(resultPage.hasNext())
                .hasPrevious(resultPage.hasPrevious())
                .build();
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
