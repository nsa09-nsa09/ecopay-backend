package kz.hrms.splitupauth.entity;

/**
 * Catalog of user-facing notification events. Each type carries:
 *
 * <ul>
 *   <li>{@link #category} — the grouping shown in the preferences UI, so users toggle a small set
 *       of buckets rather than ~20 individual types.
 *   <li>{@link #emailEligible} — whether this type may ever be sent over email. Low-signal events
 *       (e.g. a new applicant) are in-app only regardless of the user's email preference;
 *       high-importance ones (payment failed, dispute resolved, payout, ban) can additionally
 *       email.
 * </ul>
 *
 * The actual decision to deliver on a channel is preference ∧ eligibility — see {@code
 * NotificationService}.
 */
public enum NotificationType {

  // ---- membership lifecycle ----
  APPLICATION_SENT(NotificationCategory.MEMBERSHIP, true),
  MEMBER_JOINED(NotificationCategory.MEMBERSHIP, false),
  PAYMENT_SUCCESS(NotificationCategory.PAYMENTS, true),
  PAYMENT_FAILED(NotificationCategory.PAYMENTS, true),
  // Owner-facing: a member completed payment and is now requesting connection/access.
  // In-app only — one per member payment would be too noisy over email.
  ROOM_MEMBER_PAID(NotificationCategory.MEMBERSHIP, false),
  OWNER_ACCESS_GRANTED(NotificationCategory.MEMBERSHIP, true),
  MEMBER_ACCESS_CONFIRMED(NotificationCategory.MEMBERSHIP, true),
  MEMBER_CONFIRMED(NotificationCategory.MEMBERSHIP, false),
  MEMBERSHIP_ACTIVATED(NotificationCategory.MEMBERSHIP, true),
  MEMBERSHIP_REJECTED(NotificationCategory.MEMBERSHIP, true),
  CONFIRMATION_DEADLINE_WARNING(NotificationCategory.MEMBERSHIP, true),

  // ---- room lifecycle ----
  ROOM_ACTIVE(NotificationCategory.ROOM, false),
  // Major owner-facing event: every seat is now paid and awaiting access — also emailed.
  ROOM_FULL_AWAITING_ACCESS(NotificationCategory.ROOM, true),
  // A new message in a room chat, to the other participants. In-app only.
  CHAT_MESSAGE(NotificationCategory.ROOM, false),
  ROOM_COMPLETED(NotificationCategory.ROOM, false),
  ROOM_BLOCKED(NotificationCategory.ROOM, true),
  ROOM_CANCELLED(NotificationCategory.ROOM, true),

  // ---- money out ----
  REFUND_ISSUED(NotificationCategory.PAYMENTS, true),
  PAYOUT_SENT(NotificationCategory.PAYMENTS, true),

  // ---- disputes & support ----
  DISPUTE_OPENED(NotificationCategory.DISPUTES, true),
  DISPUTE_RESOLVED(NotificationCategory.DISPUTES, true),
  TICKET_REPLY(NotificationCategory.SUPPORT, true),

  // ---- account ----
  ACCOUNT_BANNED(NotificationCategory.ACCOUNT, true),
  ACCOUNT_UNBANNED(NotificationCategory.ACCOUNT, true);

  private final NotificationCategory category;
  private final boolean emailEligible;

  NotificationType(NotificationCategory category, boolean emailEligible) {
    this.category = category;
    this.emailEligible = emailEligible;
  }

  public NotificationCategory getCategory() {
    return category;
  }

  public boolean isEmailEligible() {
    return emailEligible;
  }
}
