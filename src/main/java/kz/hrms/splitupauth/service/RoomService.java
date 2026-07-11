package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kz.hrms.splitupauth.dto.CreateRoomRequest;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.dto.RoomFilter;
import kz.hrms.splitupauth.dto.RoomInviteLinkDto;
import kz.hrms.splitupauth.dto.RoomResponse;
import kz.hrms.splitupauth.dto.RoomSummaryDto;
import kz.hrms.splitupauth.dto.UpdateRoomRequest;
import kz.hrms.splitupauth.entity.AccessType;
import kz.hrms.splitupauth.entity.Category;
import kz.hrms.splitupauth.entity.ConnectionType;
import kz.hrms.splitupauth.entity.Currency;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.PeriodType;
import kz.hrms.splitupauth.entity.ProviderType;
import kz.hrms.splitupauth.entity.Review;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.RoomType;
import kz.hrms.splitupauth.entity.ServiceEntity;
import kz.hrms.splitupauth.entity.TariffPlan;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.VerificationMode;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.exception.TooManyRequestsException;
import kz.hrms.splitupauth.repository.CategoryRepository;
import kz.hrms.splitupauth.repository.OwnerRatingProjection;
import kz.hrms.splitupauth.repository.PayoutMethodRepository;
import kz.hrms.splitupauth.repository.ReviewRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomOccupancyProjection;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.ServiceRepository;
import kz.hrms.splitupauth.repository.TariffPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
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
  private final ExchangeRateService exchangeRateService;
  private final ReputationService reputationService;
  private final PayoutMethodRepository payoutMethodRepository;

  /** Member statuses that occupy a seat (see CLAUDE.md). */
  private static final List<MemberStatus> OCCUPYING_STATUSES =
      List.of(MemberStatus.PENDING, MemberStatus.ACTIVE);

  /** Non-terminal room statuses that count toward an owner's active-room cap. */
  private static final List<RoomStatus> ACTIVE_OWNER_STATUSES =
      List.of(RoomStatus.OPEN, RoomStatus.IN_VERIFICATION, RoomStatus.ACTIVE);

  /**
   * Hard ceiling on how many live rooms a single owner may hold at once (OPEN / IN_VERIFICATION /
   * ACTIVE). Anti-abuse backstop on top of the per-time rate limit in {@code RoomController}. Tune
   * via env without a redeploy. 0 or negative disables the cap.
   */
  @Value("${app.rate-limit.room-create.max-active-per-owner:10}")
  private int maxActiveRoomsPerOwner;

  /**
   * Public host of the SPA. Used to build copy-pasteable invite links — we deliberately do not
   * derive this from the current request so the link is correct from background contexts too (and
   * is unaffected by a misconfigured reverse proxy that forwards localhost host headers).
   */
  @Value("${app.frontend-url:http://localhost:5173}")
  private String frontendUrl;

  /**
   * Field-injected so the {@link #RoomService} constructor (Lombok-generated via
   * {@code @RequiredArgsConstructor}) and the unit-test wiring don't have to change. Only used by
   * the startup validator below.
   */
  @Autowired(required = false)
  private Environment env;

  /**
   * Defence-in-depth: fail fast on a missing or blank {@code app.frontend-url}, and shout when a
   * non-dev profile is still pointing at localhost so a misconfigured deployment doesn't silently
   * mint invite links nobody can follow. Dev profiles are allowed to use localhost as their normal
   * URL.
   */
  @PostConstruct
  void validateFrontendUrl() {
    if (frontendUrl == null || frontendUrl.isBlank()) {
      throw new IllegalStateException("app.frontend-url must be configured");
    }
    if (frontendUrl.contains("localhost") && !isDevProfile()) {
      log.warn(
          "app.frontend-url is set to a localhost address ({}). "
              + "Invite links will point to localhost. "
              + "Set APP_FRONTEND_URL env var for production.",
          frontendUrl);
    }
  }

  private boolean isDevProfile() {
    if (env == null) {
      return false;
    }
    return java.util.Arrays.asList(env.getActiveProfiles()).contains("dev");
  }

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
          "Invalid room status transition: " + currentStatus + " -> " + targetStatus);
    }
  }

  @Transactional
  public RoomResponse createRoom(User currentUser, CreateRoomRequest request) {
    if (currentUser.getPhoneVerifiedAt() == null) {
      throw new ForbiddenOperationException("Verify your phone number before creating a room");
    }

    // Owners are paid out monthly to a connected card, so a room cannot exist without one.
    boolean hasPayoutCard =
        payoutMethodRepository
            .findByUserAndIsDefaultTrueAndStatus(currentUser, "ACTIVE")
            .isPresent();
    if (!hasPayoutCard) {
      throw new ForbiddenOperationException(
          "Connect a payout card before creating a room — owner payouts require an active card");
    }

    if (maxActiveRoomsPerOwner > 0) {
      long activeRooms =
          roomRepository.countByOwnerAndDeletedAtIsNullAndStatusIn(
              currentUser, ACTIVE_OWNER_STATUSES);
      if (activeRooms >= maxActiveRoomsPerOwner) {
        throw new TooManyRequestsException(
            "Достигнут лимит активных комнат ("
                + maxActiveRoomsPerOwner
                + "). Завершите или отмените существующие комнаты, прежде чем создавать новые.");
      }
    }

    Category category = null;
    if (request.getCategoryId() != null) {
      category =
          categoryRepository
              .findById(request.getCategoryId())
              .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
    }

    ServiceEntity service =
        serviceRepository
            .findById(request.getServiceId())
            .filter(serviceEntity -> Boolean.TRUE.equals(serviceEntity.getIsActive()))
            .orElseThrow(() -> new ResourceNotFoundException("Service not found"));

    TariffPlan tariffPlan =
        tariffPlanRepository
            .findById(request.getTariffPlanId())
            .filter(tp -> Boolean.TRUE.equals(tp.getIsActive()))
            .orElseThrow(() -> new ResourceNotFoundException("Tariff plan not found"));

    if (!tariffPlan.getService().getId().equals(service.getId())) {
      throw new InvalidRequestException("Tariff plan does not belong to the selected service");
    }

    // Тип комнаты определяется каталогом сервиса, а не клиентом.
    RoomType derivedType =
        service.getProviderType() == ProviderType.DIGITAL ? RoomType.DIGITAL : RoomType.TELECOM;
    request.setRoomType(derivedType);
    // Способ подключения задаёт админ на тарифе — наследуем, если клиент не прислал.
    if (derivedType == RoomType.TELECOM && request.getConnectionType() == null) {
      request.setConnectionType(tariffPlan.getConnectionType());
    }

    validateCreateRequest(request);

    // Hybrid access/restrictions: request value wins, else inherit tariff defaults.
    JsonNode rules = tariffRules(tariffPlan);
    // Priority: explicit request value > tariff default rule > fallback by connection method.
    // The create-room form does not collect accessType, so for tariffs whose operator_rules
    // omit defaultAccessType we derive a sensible default instead of failing publication.
    AccessType accessType =
        request.getAccessType() != null
            ? request.getAccessType()
            : enumRule(rules, "defaultAccessType");
    if (accessType == null) {
      accessType = defaultAccessFor(request.getConnectionType());
    }
    String regionRestriction =
        request.getRegionRestriction() != null
            ? request.getRegionRestriction()
            : textRule(rules, "region");
    Boolean requiresEmailForInvite =
        request.getRequiresEmailForInvite() != null
            ? request.getRequiresEmailForInvite()
            : boolRule(rules, "requiresEmailForInvite");
    Boolean emailChangeForbidden =
        request.getEmailChangeForbidden() != null
            ? request.getEmailChangeForbidden()
            : boolRule(rules, "emailChangeForbidden");
    Integer accessGrantSlaHours =
        request.getAccessGrantSlaHours() != null
            ? request.getAccessGrantSlaHours()
            : intRule(rules, "accessGrantSlaHours");
    // Surface the catalog sharing warning as the room's restriction note when none provided.
    String operatorRestrictions =
        request.getOperatorRestrictions() != null
            ? request.getOperatorRestrictions()
            : textRule(rules, "sharingWarning");

    // Price, currency, billing period, and seat count are owned by the admin-managed
    // tariff — NOT the room owner. Snapshot them from the tariff so editing a tariff
    // later never silently re-prices existing rooms. The per-member share is the total
    // split equally across the seats the admin configured.
    Integer maxMembers = tariffPlan.getMaxMembers();
    PeriodType periodType = tariffPlan.getPeriodType();
    java.math.BigDecimal priceTotal = tariffPlan.getBasePriceTotal();
    java.math.BigDecimal pricePerMember = priceTotal;
    if (maxMembers != null && maxMembers > 0) {
      pricePerMember =
          priceTotal.divide(
              java.math.BigDecimal.valueOf(maxMembers), 2, java.math.RoundingMode.HALF_UP);
    }

    // Resolve currency (whitelist check) + freeze the FX snapshot at creation.
    String currency = Currency.normalize(tariffPlan.getCurrency()).name();
    java.math.BigDecimal fxRate = exchangeRateService.rateOf(currency);
    java.math.BigDecimal priceTotalKzt = exchangeRateService.toKzt(priceTotal, currency);
    java.math.BigDecimal pricePerMemberKzt = exchangeRateService.toKzt(pricePerMember, currency);

    Room room =
        Room.builder()
            .owner(currentUser)
            .category(category)
            .service(service)
            .tariffPlan(tariffPlan)
            .roomType(request.getRoomType())
            .verificationMode(VerificationMode.RISK_BASED)
            .status(RoomStatus.OPEN)
            .title(request.getTitle())
            .description(request.getDescription())
            .maxMembers(maxMembers)
            .priceTotal(priceTotal)
            .pricePerMember(pricePerMember)
            .currency(currency)
            .fxRateToKzt(fxRate)
            .priceTotalKzt(priceTotalKzt)
            .pricePerMemberKzt(pricePerMemberKzt)
            .periodType(periodType)
            .startDate(request.getStartDate() != null ? request.getStartDate() : LocalDateTime.now().plusYears(1))
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

    roomEventLogger.log(
        room,
        null,
        currentUser,
        "OWNER",
        "room_created",
        java.util.Map.of(
            "roomType",
            String.valueOf(room.getRoomType()),
            "maxMembers",
            room.getMaxMembers(),
            "pricePerMember",
            String.valueOf(room.getPricePerMember())));

    return roomMapper.toResponse(room);
  }

  @Transactional(readOnly = true)
  public RoomResponse getRoom(Long roomId) {
    Room room =
        roomRepository
            .findById(roomId)
            .filter(r -> r.getDeletedAt() == null)
            .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

    RoomResponse response = roomMapper.toResponse(room);
    applyOwnerRating(response, room.getOwner().getId());
    int memberOccupied =
        (int)
            roomMemberRepository.countByRoomAndStatusInAndDeletedAtIsNull(room, OCCUPYING_STATUSES);
    // Owner always occupies 1 slot — they created the room.
    int occupied = Math.min(response.getMaxMembers(), 1 + memberOccupied);
    response.setFilledSeats(occupied);
    response.setFreeSeats(Math.max(0, response.getMaxMembers() - occupied));
    return response;
  }

  @Transactional(readOnly = true)
  public PagedResponse<RoomSummaryDto> getRooms(
      int page, int size, RoomFilter filter, String sortBy, String sortDir) {
    Pageable pageable = buildPageable(page, size, sortBy, sortDir);

    RoomFilter f = filter != null ? filter : RoomFilter.builder().build();

    Specification<Room> spec = (root, q, cb) -> cb.isNull(root.get("deletedAt"));
    if (f.getStatus() != null) {
      spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), f.getStatus()));
    }
    if (f.getRoomType() != null) {
      spec = spec.and((root, q, cb) -> cb.equal(root.get("roomType"), f.getRoomType()));
    }
    if (f.getCategoryId() != null) {
      spec = spec.and((root, q, cb) -> cb.equal(root.get("category").get("id"), f.getCategoryId()));
    }
    if (f.getServiceId() != null) {
      spec = spec.and((root, q, cb) -> cb.equal(root.get("service").get("id"), f.getServiceId()));
    }
    if (f.getPriceMin() != null) {
      spec =
          spec.and(
              (root, q, cb) ->
                  cb.greaterThanOrEqualTo(root.get("pricePerMember"), f.getPriceMin()));
    }
    if (f.getPriceMax() != null) {
      spec =
          spec.and(
              (root, q, cb) -> cb.lessThanOrEqualTo(root.get("pricePerMember"), f.getPriceMax()));
    }
    if (f.getAccessType() != null) {
      spec = spec.and((root, q, cb) -> cb.equal(root.get("accessType"), f.getAccessType()));
    }
    if (f.getRegion() != null && !f.getRegion().isBlank()) {
      spec = spec.and((root, q, cb) -> cb.equal(root.get("regionRestriction"), f.getRegion()));
    }
    if (Boolean.TRUE.equals(f.getVerifiedOwnerOnly())) {
      spec = spec.and((root, q, cb) -> cb.isTrue(root.get("owner").get("ownerVerified")));
    }
    if (f.getMinFreeSeats() != null && f.getMinFreeSeats() > 0) {
      int minFree = f.getMinFreeSeats();
      spec =
          spec.and(
              (root, q, cb) -> {
                // maxMembers - occupied >= minFree  ⇔  occupied <= maxMembers - minFree
                Subquery<Long> occupied = occupiedSubquery(q, cb, root);
                return cb.le(occupied, cb.diff(root.<Integer>get("maxMembers"), minFree));
              });
    }

    // Sorting by computed fields (free seats / owner rating / popularity) is applied
    // as an ORDER BY inside the Specification when the Pageable carries no sort.
    if (isComputedSort(sortBy)) {
      spec = spec.and(computedOrderSpec(sortBy.trim()));
    }

    Page<Room> resultPage = roomRepository.findAll(spec, pageable);
    return toPagedResponse(resultPage);
  }

  /** Correlated subquery counting seat-occupying (PENDING/ACTIVE) members of {@code roomRoot}. */
  private Subquery<Long> occupiedSubquery(
      CriteriaQuery<?> q, CriteriaBuilder cb, Root<Room> roomRoot) {
    Subquery<Long> sub = q.subquery(Long.class);
    Root<RoomMember> m = sub.from(RoomMember.class);
    sub.select(cb.count(m));
    sub.where(
        cb.equal(m.get("room"), roomRoot),
        cb.isNull(m.get("deletedAt")),
        m.get("status").in(OCCUPYING_STATUSES));
    return sub;
  }

  @Transactional(readOnly = true)
  public PagedResponse<RoomSummaryDto> getMyRooms(User user, int page, int size) {
    Pageable pageable = buildPageable(page, size, null, null);

    Specification<Room> spec =
        (root, q, cb) ->
            cb.and(cb.isNull(root.get("deletedAt")), cb.equal(root.get("owner"), user));

    Page<Room> resultPage = roomRepository.findAll(spec, pageable);
    return toPagedResponse(resultPage);
  }

  /** Sort keys that cannot map to a single entity column (ordered via Specification ORDER BY). */
  private static final Set<String> COMPUTED_SORTS = Set.of("most_seats", "best_rating", "popular");

  private boolean isComputedSort(String sortBy) {
    return sortBy != null && COMPUTED_SORTS.contains(sortBy.trim());
  }

  private Pageable buildPageable(int page, int size, String sortBy, String sortDir) {
    if (page < 0) page = 0;
    if (size <= 0) size = 20;
    if (size > 100) size = 100;
    // Computed sorts carry no Pageable sort; ORDER BY is applied inside the Specification.
    if (isComputedSort(sortBy)) {
      return PageRequest.of(page, size);
    }
    return PageRequest.of(page, size, entitySort(sortBy, sortDir));
  }

  private Sort entitySort(String sortBy, String sortDir) {
    String key = sortBy == null ? "" : sortBy.trim();
    switch (key) {
      case "price_asc":
        return Sort.by(Sort.Direction.ASC, "pricePerMember");
      case "price_desc":
        return Sort.by(Sort.Direction.DESC, "pricePerMember");
      case "newest":
        return Sort.by(Sort.Direction.DESC, "createdAt");
      case "starting_soon":
        return Sort.by(Sort.Direction.ASC, "startDate");
      default:
        // Legacy: treat sortBy as a raw entity field with sortDir, default createdAt desc.
        String field = key.isBlank() ? "createdAt" : key;
        Sort.Direction direction =
            "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(direction, field);
    }
  }

  /** Apply ORDER BY for a computed sort key. Skipped for the count query (Long result type). */
  private Specification<Room> computedOrderSpec(String key) {
    return (root, q, cb) -> {
      Class<?> resultType = q.getResultType();
      if (resultType == Long.class || resultType == long.class) {
        return cb.conjunction();
      }
      switch (key) {
        case "most_seats":
          {
            // free seats = maxMembers - occupied, descending
            Subquery<Long> occupied = occupiedSubquery(q, cb, root);
            q.orderBy(cb.desc(cb.diff(root.<Integer>get("maxMembers"), occupied)));
            break;
          }
        case "best_rating":
          {
            Subquery<Double> avg = q.subquery(Double.class);
            Root<Review> rv = avg.from(Review.class);
            avg.select(cb.avg(rv.get("rating")));
            avg.where(
                cb.equal(rv.get("recipient"), root.get("owner")),
                cb.isFalse(rv.get("hiddenByAdmin")));
            q.orderBy(cb.desc(cb.coalesce(avg, 0.0)));
            break;
          }
        case "popular":
          {
            Subquery<Long> cnt = q.subquery(Long.class);
            Root<RoomMember> m = cnt.from(RoomMember.class);
            cnt.select(cb.count(m));
            cnt.where(cb.equal(m.get("room"), root), cb.isNull(m.get("deletedAt")));
            q.orderBy(cb.desc(cnt));
            break;
          }
        default:
          break;
      }
      return cb.conjunction();
    };
  }

  private PagedResponse<RoomSummaryDto> toPagedResponse(Page<Room> resultPage) {
    List<RoomSummaryDto> items =
        resultPage.getContent().stream().map(roomMapper::toSummary).toList();
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
    Set<Long> ownerIds =
        summaries.stream().map(RoomSummaryDto::getOwnerUserId).collect(Collectors.toSet());
    Map<Long, OwnerRatingProjection> byOwner =
        reviewRepository.aggregateRatingByRecipientIds(ownerIds).stream()
            .collect(Collectors.toMap(OwnerRatingProjection::getRecipientId, p -> p));
    for (RoomSummaryDto summary : summaries) {
      OwnerRatingProjection p = byOwner.get(summary.getOwnerUserId());
      summary.setOwnerRating(roundRating(p));
      summary.setOwnerReviewCount(
          p != null && p.getReviewCount() != null ? p.getReviewCount().intValue() : 0);
    }
  }

  /** Batch occupied-seat counts for a page of summaries (one query, no N+1). */
  private void enrichSeats(List<RoomSummaryDto> summaries) {
    if (summaries.isEmpty()) {
      return;
    }
    Set<Long> roomIds = summaries.stream().map(RoomSummaryDto::getId).collect(Collectors.toSet());
    Map<Long, Long> occupiedByRoom =
        roomMemberRepository.countOccupiedByRoomIds(roomIds, OCCUPYING_STATUSES).stream()
            .collect(
                Collectors.toMap(
                    RoomOccupancyProjection::getRoomId, RoomOccupancyProjection::getOccupied));
    for (RoomSummaryDto summary : summaries) {
      int memberOccupied = occupiedByRoom.getOrDefault(summary.getId(), 0L).intValue();
      int max = summary.getMaxMembers() != null ? summary.getMaxMembers() : 0;
      // Owner always occupies 1 slot — they created the room.
      int occupied = Math.min(max, 1 + memberOccupied);
      summary.setFilledSeats(occupied);
      summary.setFreeSeats(Math.max(0, max - occupied));
    }
  }

  private void applyOwnerRating(RoomResponse response, Long ownerId) {
    List<OwnerRatingProjection> rows =
        reviewRepository.aggregateRatingByRecipientIds(List.of(ownerId));
    OwnerRatingProjection p = rows.isEmpty() ? null : rows.get(0);
    response.setOwnerRating(roundRating(p));
    response.setOwnerReviewCount(
        p != null && p.getReviewCount() != null ? p.getReviewCount().intValue() : 0);
  }

  private Double roundRating(OwnerRatingProjection p) {
    if (p == null || p.getAvgRating() == null) {
      return null;
    }
    return Math.round(p.getAvgRating() * 10.0) / 10.0;
  }

  @Transactional
  public RoomResponse updateRoom(Long roomId, User currentUser, UpdateRoomRequest request) {
    Room room =
        roomRepository
            .findById(roomId)
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

    // Seat count and pricing are tariff-controlled and not editable by the owner.

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
    Room room =
        roomRepository
            .findById(roomId)
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
    List<Room> roomsToTransition =
        roomRepository
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
    // Price/currency/period/seat count are validated on the tariff plan itself
    // (admin-managed) and snapshotted onto the room — nothing to validate here.

    if (request.getStartDate() != null && request.getStartDate().isBefore(LocalDateTime.now())) {
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
      throw new InvalidRequestException(
          "Either positive priceTotal or positive pricePerMember must be provided");
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

  /** Last-resort access type when neither the request nor the tariff rules specify one. */
  private AccessType defaultAccessFor(ConnectionType connectionType) {
    if (connectionType == null) {
      return AccessType.FAMILY_PLAN;
    }
    return switch (connectionType) {
      case SIM, ESIM -> AccessType.FAMILY_PLAN; // operator adds the member to a family/group plan
      case ACCOUNT_LINK -> AccessType.INVITE_LINK; // invite into the operator account
      case OTHER -> AccessType.FAMILY_PLAN;
    };
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
    Room room =
        roomRepository
            .findById(roomId)
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
    Room room =
        roomRepository
            .findById(roomId)
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

    // A completed room counts toward activity-based reputation for the owner and everyone
    // who actively participated, so refresh their scores now.
    reputationService.recompute(room.getOwner());
    roomMemberRepository.findByRoomAndDeletedAtIsNullOrderByCreatedAtAsc(room).stream()
        .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
        .map(RoomMember::getUser)
        .forEach(reputationService::recompute);

    return roomMapper.toResponse(room);
  }

  @SuppressWarnings("unused")
  @Transactional
  public RoomResponse blockRoom(Long roomId, User currentUser, String reason) {
    Room room =
        roomRepository
            .findById(roomId)
            .filter(r -> r.getDeletedAt() == null)
            .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

    ensureStatusTransition(room.getStatus(), RoomStatus.BLOCKED);

    room.setStatus(RoomStatus.BLOCKED);
    //        room.setBlockedReason(reason);
    room.setBlockedAt(LocalDateTime.now());

    room = roomRepository.save(room);

    return roomMapper.toResponse(room);
  }

  /**
   * Returns (or lazily mints) the copy-pasteable invite link for the room's owner. The token is
   * opaque, single-room-scoped, and the URL is built against {@code app.frontend-url} — so it stays
   * correct under the prod domain instead of leaking whatever host the backend sees.
   *
   * <p>Auth: owner-only. Non-owners get a 403 instead of leaking that the room even has a token.
   */
  @Transactional
  public RoomInviteLinkDto getOrCreateInviteLink(Long roomId, User currentUser) {
    Room room =
        roomRepository
            .findById(roomId)
            .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

    if (room.getOwner() == null
        || currentUser == null
        || !room.getOwner().getId().equals(currentUser.getId())) {
      throw new ForbiddenOperationException("Only the room owner can issue an invite link");
    }

    if (room.getInviteToken() == null || room.getInviteToken().isBlank()) {
      // Hex-only, 32 chars — fits the VARCHAR(40) column with room to
      // spare and stays URL-safe without any encoding step.
      room.setInviteToken(UUID.randomUUID().toString().replace("-", ""));
      room = roomRepository.save(room);
    }

    String base = sanitizeFrontendBase(frontendUrl);
    String url = base + "/room/" + room.getId() + "?invite=" + room.getInviteToken();

    return RoomInviteLinkDto.builder().url(url).token(room.getInviteToken()).build();
  }

  /**
   * Strips the Vite dev port (:5173) from a configured frontend base so it never leaks into any
   * user-facing link (invite URLs, email links, etc.). Also trims trailing slashes.
   */
  private static String sanitizeFrontendBase(String url) {
    if (url == null) return "";
    return url.replaceAll("/+$", "").replaceFirst(":5173(?=/|$)", "");
  }
}
