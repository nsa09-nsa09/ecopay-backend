package kz.hrms.splitupauth.repository;

/** Number of occupied seats (PENDING + ACTIVE members) for a room. */
public interface RoomOccupancyProjection {
    Long getRoomId();
    Long getOccupied();
}
