package kz.hrms.splitupauth.entity;

/**
 * Editorial lifecycle of a {@link News} item. Only {@link #PUBLISHED} entries
 * are exposed on the public feed; {@link #DRAFT} is the default for newly
 * created rows and {@link #ARCHIVED} is the soft-delete state used when an
 * item is taken off the feed but kept for the audit trail.
 */
public enum NewsStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED
}
