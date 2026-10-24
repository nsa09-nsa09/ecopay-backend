package kz.hrms.splitupauth.dto;

import java.time.LocalDateTime;

public record DeletedUserAdminDto(
    Long userId,
    String publicId,
    String displayNameAtDeletion,
    String slugAtDeletion,
    String emailMasked,
    String phoneMasked,
    LocalDateTime deletedAt,
    boolean identityArchived) {}
