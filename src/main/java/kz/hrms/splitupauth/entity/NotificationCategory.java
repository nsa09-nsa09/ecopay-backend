package kz.hrms.splitupauth.entity;

/**
 * Preference-UI grouping for {@link NotificationType}. Users toggle channels
 * per category indirectly — the preferences API expands a category into its
 * member types. Kept deliberately small so the settings screen stays readable.
 */
public enum NotificationCategory {
    MEMBERSHIP,
    ROOM,
    PAYMENTS,
    DISPUTES,
    SUPPORT,
    ACCOUNT
}
