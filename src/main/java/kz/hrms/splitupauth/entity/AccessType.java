package kz.hrms.splitupauth.entity;

/**
 * How the owner grants access to members of a room.
 * FAMILY_PLAN   — owner adds members to an official family/group plan
 * SHARED_ACCOUNT— owner shares a single account login
 * INVITE_LINK   — owner sends an invite link
 * EMAIL_INVITE  — owner invites by the member's own email
 */
public enum AccessType {
    FAMILY_PLAN,
    SHARED_ACCOUNT,
    INVITE_LINK,
    EMAIL_INVITE
}
