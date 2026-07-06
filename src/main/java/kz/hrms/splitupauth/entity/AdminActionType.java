package kz.hrms.splitupauth.entity;

public enum AdminActionType {
  ACCESS_CONFIRMED,
  ACCESS_REJECTED,
  ROOM_BLOCKED,
  USER_BANNED,
  USER_UNBANNED,
  USER_CREATED,
  USER_ROLE_CHANGED,
  REFUND_INITIATED,
  REFUND_APPROVED,
  REFUND_REJECTED,
  DISPUTE_RESOLVED,
  BATCH_CONFIRM,
  OWNER_VERIFICATION_CHANGED,
  CATEGORY_CREATED,
  CATEGORY_UPDATED,
  CATEGORY_DELETED,
  SERVICE_CREATED,
  SERVICE_UPDATED,
  SERVICE_DELETED,
  TARIFF_CREATED,
  TARIFF_UPDATED,
  TARIFF_DELETED,
  TESTIMONIAL_FEATURED,
  TESTIMONIAL_UNFEATURED,
  TESTIMONIAL_EDITED,
  TESTIMONIAL_DELETED,
  SITE_CONTENT_UPDATED,
  FEEDBACK_STATUS_CHANGED,
  FEEDBACK_NOTE_UPDATED,
  NEWS_CREATED,
  NEWS_UPDATED,
  NEWS_DELETED,
  LEGAL_DOCUMENT_UPDATED,
  /**
   * Read-only sentinel used by {@code AdminActionTypeConverter} when the DB row carries an
   * action_type that this build's enum doesn't know about (e.g. an older deployment wrote a value
   * that has since been removed, or a newer deployment wrote one this build hasn't shipped yet).
   * Keeps the admin-logs endpoint from 500-ing on a forwards/backwards mismatch. Never written back
   * to the database — the CHECK constraint would reject it — and never produced by any
   * business-logic write path.
   */
  UNKNOWN
}
