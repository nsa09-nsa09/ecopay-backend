package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.entity.Room;

/** Shared room capacity math for EcoPay marketplace seats. */
final class RoomSeatMath {

  static final int DEFAULT_EXISTING_MEMBERS_COUNT = 1;

  private RoomSeatMath() {}

  static int existingMembersCount(Room room) {
    Integer value = room == null ? null : room.getExistingMembersCount();
    return value == null ? DEFAULT_EXISTING_MEMBERS_COUNT : value;
  }

  static int marketplaceCapacity(Room room) {
    int maxMembers = room == null || room.getMaxMembers() == null ? 0 : room.getMaxMembers();
    return Math.max(0, maxMembers - existingMembersCount(room));
  }

  static int filledSeats(Room room, long marketplaceOccupied) {
    int maxMembers = room == null || room.getMaxMembers() == null ? 0 : room.getMaxMembers();
    return Math.min(maxMembers, existingMembersCount(room) + safeInt(marketplaceOccupied));
  }

  static int freeSeats(Room room, long marketplaceOccupied) {
    return Math.max(0, marketplaceCapacity(room) - safeInt(marketplaceOccupied));
  }

  static boolean marketplaceFull(Room room, long marketplaceOccupied) {
    return marketplaceOccupied >= marketplaceCapacity(room);
  }

  private static int safeInt(long value) {
    if (value <= 0L) {
      return 0;
    }
    return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
  }
}
