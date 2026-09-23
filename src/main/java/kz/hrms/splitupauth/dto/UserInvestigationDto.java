package kz.hrms.splitupauth.dto;

import java.time.LocalDateTime;
import java.util.List;

public record UserInvestigationDto(
    AdminUserDto user,
    List<OwnedRoom> ownedRooms,
    List<Membership> memberships,
    List<UserReportDto> reportsAgainst,
    List<UserReportDto> reportsBy,
    long supportTicketsCount,
    long disputesCount,
    List<RoomEvent> recentRoomEvents,
    List<AdminAction> recentAdminActions) {
  public record OwnedRoom(
      Long roomId, String title, String status, LocalDateTime createdAt, LocalDateTime deletedAt) {}

  public record Membership(
      Long roomId,
      String roomTitle,
      String memberStatus,
      LocalDateTime joinedAt,
      LocalDateTime endedAt) {}

  public record RoomEvent(Long id, Long roomId, String eventType, LocalDateTime createdAt) {}

  public record AdminAction(Long id, String actionType, LocalDateTime createdAt) {}
}
