package kz.hrms.splitupauth.entity;

public enum MemberStatus {
  APPLIED,
  PENDING,
  ACTIVE,
  REJECTED,
  CANCELLED_BEFORE_PAYMENT,
  /**
   * B10: terminal state for a member whose room the owner cancelled after the member had already
   * paid. Separate from {@link #CANCELLED_BEFORE_PAYMENT} because that one implies "no money
   * changed hands"; here the member had a successful charge which the cancellation refunds.
   */
  ROOM_CANCELLED,
  BLOCKED_BY_ADMIN,
  /**
   * C4: terminal state for an ACTIVE membership an admin ended because renewal was missed past
   * grace. Distinct from {@link #CANCELLED_BEFORE_PAYMENT} (no money moved) and {@link
   * #ROOM_CANCELLED} (the room itself was cancelled). The slot is freed; the member keeps the paid
   * period's history.
   */
  EXPIRED
}
