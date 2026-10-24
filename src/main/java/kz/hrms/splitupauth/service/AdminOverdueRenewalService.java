package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kz.hrms.splitupauth.dto.AdminOverdueRenewalDto;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.entity.AdminActionLog;
import kz.hrms.splitupauth.entity.AdminActionType;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.NotificationType;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.util.ClientIp;
import kz.hrms.splitupauth.util.TextSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C4: admin screen behind {@code GET /admin/finance/overdue-renewals} + {@code POST
 * /admin/finance/overdue-renewals/{memberId}/end}. The scheduler stamps {@code renewalOverdueSince}
 * on ACTIVE members whose period lapsed past grace; this service lists them and lets an admin
 * terminate the participation with status EXPIRED.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminOverdueRenewalService {

  private static final int MAX_PAGE_SIZE = 100;

  private final RoomMemberRepository roomMemberRepository;
  private final AdminActionLogRepository adminActionLogRepository;
  private final PaymentService paymentService;
  private final NotificationService notificationService;
  private final RoomEventLogger roomEventLogger;
  private final Clock clock;

  @Transactional(readOnly = true)
  public PagedResponse<AdminOverdueRenewalDto> list(int page, int size) {
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize);
    Page<RoomMember> rows = roomMemberRepository.findOverdueRenewals(pageable);
    LocalDateTime now = LocalDateTime.now(clock);
    List<AdminOverdueRenewalDto> items =
        rows.getContent().stream().map(m -> toDto(m, now)).toList();
    return PagedResponse.<AdminOverdueRenewalDto>builder()
        .items(items)
        .page(rows.getNumber())
        .size(rows.getSize())
        .totalItems(rows.getTotalElements())
        .totalPages(rows.getTotalPages())
        .hasNext(rows.hasNext())
        .hasPrevious(rows.hasPrevious())
        .build();
  }

  @Transactional
  public AdminOverdueRenewalDto end(
      Long memberId, User admin, String rawReason, HttpServletRequest http) {
    String reason = TextSanitizer.sanitize(rawReason);
    if (reason == null || reason.isBlank()) {
      throw new InvalidRequestException("REASON_REQUIRED", "Reason is required");
    }
    RoomMember member =
        roomMemberRepository
            .findWithLockById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
    if (member.getDeletedAt() != null || member.getStatus() != MemberStatus.ACTIVE) {
      throw new InvalidRequestException(
          "MEMBER_NOT_OVERDUE", "Only ACTIVE memberships can be ended as overdue");
    }
    if (member.getRenewalOverdueSince() == null) {
      throw new InvalidRequestException(
          "MEMBER_NOT_OVERDUE", "Member is not past the renewal grace window");
    }

    LocalDateTime now = LocalDateTime.now(clock);
    MemberStatus previous = member.getStatus();
    member.setStatus(MemberStatus.EXPIRED);
    member.setEndedAt(now);
    roomMemberRepository.save(member);

    ObjectNode oldState = JsonNodeFactory.instance.objectNode();
    oldState.put("status", previous.name());
    ObjectNode newState = JsonNodeFactory.instance.objectNode();
    newState.put("status", MemberStatus.EXPIRED.name());
    adminActionLogRepository.save(
        AdminActionLog.builder()
            .eventId(UUID.randomUUID())
            .adminUser(admin)
            .actionType(AdminActionType.MEMBER_EXPIRED)
            .entityType("ROOM_MEMBER")
            .entityId(member.getId())
            .reason(reason)
            .oldState(oldState)
            .newState(newState)
            .ipAddress(http == null ? null : ClientIp.of(http))
            .userAgent(http == null ? null : http.getHeader("User-Agent"))
            .build());

    Room room = member.getRoom();
    if (room != null) {
      roomEventLogger.log(
          room,
          member,
          admin,
          "ADMIN",
          "member_expired",
          Map.of(
              "reason", reason,
              "previousStatus", previous.name(),
              "endedAt", now.toString()));
      Map<String, String> params =
          Map.of("roomTitle", room.getTitle() == null ? "" : room.getTitle(), "reason", reason);
      if (member.getUser() != null) {
        notificationService.notify(
            member.getUser(),
            NotificationType.ROOM_CANCELLED,
            params,
            "/rooms/member/" + room.getId(),
            Map.of("roomId", room.getId(), "roomMemberId", member.getId()));
      }
      if (room.getOwner() != null) {
        notificationService.notify(
            room.getOwner(),
            NotificationType.ROOM_CANCELLED,
            params,
            "/rooms/owner/" + room.getId(),
            Map.of("roomId", room.getId(), "roomMemberId", member.getId()));
      }
    }

    return toDto(member, now);
  }

  private AdminOverdueRenewalDto toDto(RoomMember member, LocalDateTime now) {
    Room room = member.getRoom();
    User user = member.getUser();
    User owner = room == null ? null : room.getOwner();
    LocalDateTime period = member.getNextBillingAt();
    long days = period == null ? 0L : Math.max(0L, Duration.between(period, now).toDays());

    java.math.BigDecimal renewalAmount = null;
    if (room != null) {
      try {
        renewalAmount = paymentService.currentChargeBreakdown(room).amount();
      } catch (RuntimeException ex) {
        log.debug(
            "Could not compute renewal amount for member {}: {}",
            member.getId(),
            ex.getClass().getSimpleName());
      }
    }

    return AdminOverdueRenewalDto.builder()
        .roomMemberId(member.getId())
        .memberUserId(user == null ? null : user.getId())
        .memberDisplayName(user == null ? null : user.getDisplayName())
        .memberEmail(user == null ? null : user.getEmail())
        .roomId(room == null ? null : room.getId())
        .roomTitle(room == null ? null : room.getTitle())
        .ownerUserId(owner == null ? null : owner.getId())
        .ownerDisplayName(owner == null ? null : owner.getDisplayName())
        .periodEndAt(period)
        .daysOverdue(days)
        .renewalAmountKzt(renewalAmount)
        .build();
  }
}
