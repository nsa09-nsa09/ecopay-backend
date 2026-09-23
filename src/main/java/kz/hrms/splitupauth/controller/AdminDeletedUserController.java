package kz.hrms.splitupauth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kz.hrms.splitupauth.dto.*;
import kz.hrms.splitupauth.entity.AdminActionLog;
import kz.hrms.splitupauth.entity.AdminActionType;
import kz.hrms.splitupauth.entity.DeletedUserIdentityArchive;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.DeletedUserIdentityArchiveRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.security.FieldEncryptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminDeletedUserController {
  private final UserRepository userRepository;
  private final DeletedUserIdentityArchiveRepository archiveRepository;
  private final FieldEncryptionService encryptionService;
  private final AdminActionLogRepository auditRepository;

  @GetMapping("/deleted")
  @Transactional(readOnly = true)
  public PagedResponse<DeletedUserAdminDto> list(
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String direction) {
    String sortField =
        switch (sort == null ? "" : sort) {
          case "userId" -> "id";
          case "publicId" -> "publicId";
          case "deletedAt" -> "deletedAt";
          default -> "deletedAt";
        };
    Sort.Direction sortDirection =
        "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
    Specification<User> spec =
        (root, query, cb) -> cb.equal(root.get("status"), UserStatus.DELETED);
    if (search != null && !search.isBlank()) {
      String like = "%" + search.trim().toLowerCase() + "%";
      Long id = null;
      try {
        id = Long.valueOf(search.trim());
      } catch (NumberFormatException ignored) {
      }
      Long searchId = id;
      spec =
          spec.and(
              (root, query, cb) -> {
                var archive = query.subquery(Long.class);
                var row = archive.from(DeletedUserIdentityArchive.class);
                archive.select(row.get("user").get("id"));
                archive.where(
                    cb.equal(row.get("user").get("id"), root.get("id")),
                    cb.or(
                        cb.like(cb.lower(row.get("slugAtDeletion")), like),
                        cb.like(cb.lower(row.get("displayNameAtDeletion")), like)));
                return cb.or(
                    cb.like(cb.lower(root.get("publicId")), like),
                    cb.exists(archive),
                    searchId == null ? cb.disjunction() : cb.equal(root.get("id"), searchId));
              });
    }
    var result =
        userRepository.findAll(
            spec,
            PageRequest.of(
                Math.max(0, page),
                size < 1 || size > 100 ? 20 : size,
                Sort.by(sortDirection, sortField)));
    Map<Long, DeletedUserIdentityArchive> archives =
        archiveRepository
            .findByUser_IdIn(result.getContent().stream().map(User::getId).toList())
            .stream()
            .collect(Collectors.toMap(a -> a.getUser().getId(), Function.identity()));
    var items =
        result.getContent().stream()
            .map(
                u -> {
                  var a = archives.get(u.getId());
                  return new DeletedUserAdminDto(
                      u.getId(),
                      u.getPublicId(),
                      a == null ? null : a.getDisplayNameAtDeletion(),
                      a == null ? null : a.getSlugAtDeletion(),
                      a == null ? null : a.getEmailMasked(),
                      a == null ? null : a.getPhoneMasked(),
                      u.getDeletedAt(),
                      a != null);
                })
            .toList();
    return PagedResponse.<DeletedUserAdminDto>builder()
        .items(items)
        .page(result.getNumber())
        .size(result.getSize())
        .totalItems(result.getTotalElements())
        .totalPages(result.getTotalPages())
        .hasNext(result.hasNext())
        .hasPrevious(result.hasPrevious())
        .build();
  }

  @PostMapping("/{userId}/deleted-identifiers/reveal")
  @Transactional
  public DeletedIdentifiersRevealDto reveal(
      @PathVariable Long userId,
      @Valid @RequestBody DeletedIdentifiersRevealRequest body,
      @AuthenticationPrincipal User admin,
      HttpServletRequest request) {
    var archive =
        archiveRepository
            .findByUser_Id(userId)
            .filter(a -> a.getUser().getStatus() == UserStatus.DELETED)
            .orElseThrow(() -> new ResourceNotFoundException("Archived identity not found"));
    auditRepository.save(
        AdminActionLog.builder()
            .adminUser(admin)
            .actionType(AdminActionType.USER_DELETED_IDENTIFIERS_REVEALED)
            .entityType("USER")
            .entityId(userId)
            .reason(body.reason())
            .ipAddress(request.getRemoteAddr())
            .userAgent(request.getHeader("User-Agent"))
            .build());
    return new DeletedIdentifiersRevealDto(
        userId,
        archive.getEmailEncrypted() == null
            ? null
            : encryptionService.decrypt(archive.getEmailEncrypted()),
        archive.getPhoneEncrypted() == null
            ? null
            : encryptionService.decrypt(archive.getPhoneEncrypted()),
        archive.getSlugAtDeletion());
  }
}
