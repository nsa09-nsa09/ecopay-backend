package kz.hrms.splitupauth.entity;

/**
 * Which contact a joining member must hand over so the owner can grant them access.
 *
 * <p>EMAIL — the owner invites by the member's own email (Spotify, YouTube, Apple One …). PHONE —
 * the owner needs the member's phone number (telecom operators, Yandex family by number). BOTH —
 * the service accepts either; the member picks.
 *
 * <p>Not to be confused with {@link AccessType}, which describes <em>how</em> the owner grants
 * access (family plan / shared account / invite link) and lives on the room, not the service.
 */
public enum ServiceAccessType {
  EMAIL,
  PHONE,
  BOTH
}
