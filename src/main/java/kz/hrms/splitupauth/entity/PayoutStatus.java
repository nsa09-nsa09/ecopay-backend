package kz.hrms.splitupauth.entity;

/**
 * Lifecycle status of an owner {@link Payout}. Introduced (Block 5c) to replace raw status strings
 * with a type-safe enum. The DB column stays {@code text} and stores the constant name verbatim via
 * {@link kz.hrms.splitupauth.entity.converter.PayoutStatusConverter}, so no data migration is
 * needed and no stored value changes.
 *
 * <p>Names match the strings previously written, so behaviour is identical:
 *
 * <ul>
 *   <li>{@code PENDING} — created, awaiting dispatch (held until {@code releaseAt}).
 *   <li>{@code PENDING_METHOD} — owner has no usable payout method yet.
 *   <li>{@code PENDING_PROVIDER} — submitted/ambiguous at the provider; reconciled by status.
 *   <li>{@code PROCESSING} — dispatch in flight.
 *   <li>{@code SUCCESS} — paid out.
 *   <li>{@code FAILED} — dispatch failed.
 *   <li>{@code REQUIRES_REVIEW} — flagged for manual finance review.
 *   <li>{@code FROZEN} — held by a payout block.
 *   <li>{@code REVERSED} — clawed back / reversed.
 *   <li>{@code BLOCKED} — administratively blocked.
 *   <li>{@code CANCELED} — canceled before dispatch.
 * </ul>
 */
public enum PayoutStatus {
  PENDING,
  PENDING_METHOD,
  PENDING_PROVIDER,
  PROCESSING,
  SUCCESS,
  FAILED,
  REQUIRES_REVIEW,
  FROZEN,
  REVERSED,
  BLOCKED,
  CANCELED,
  // Legacy values retained only so existing status-set queries (e.g. the account-deletion guard)
  // keep matching exactly what they did as strings. Not written by current code.
  ON_HOLD,
  CLAWBACK_REQUIRED
}
