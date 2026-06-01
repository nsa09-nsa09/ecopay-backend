package kz.hrms.splitupauth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.AdminDecisionRequest;
import kz.hrms.splitupauth.dto.AdminUserDto;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.entity.AdminActionLog;
import kz.hrms.splitupauth.entity.AdminActionType;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserRepository userRepository;
    private final AdminActionLogRepository adminActionLogRepository;
    private final ObjectMapper objectMapper;

    // Whitelist of API sort fields → entity property names.
    // Note: User entity does not currently have a dedicated riskScore column,
    // so "riskScore" maps to reputation as a proxy until that field is introduced.
    private static final Map<String, String> SORT_FIELD_MAP = Map.of(
            "createdAt", "createdAt",
            "displayName", "displayName",
            "email", "email",
            "reputation", "reputation",
            "riskScore", "reputation",
            "status", "status"
    );

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<PagedResponse<AdminUserDto>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction
    ) {
        if (page < 0) page = 0;
        if (size <= 0 || size > 100) size = 20;

        String sortField = SORT_FIELD_MAP.getOrDefault(sort, "createdAt");
        Sort.Direction sortDir = "asc".equalsIgnoreCase(direction)
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        var pageable = PageRequest.of(page, size, Sort.by(sortDir, sortField));

        Specification<User> spec = (root, q, cb) -> cb.conjunction();
        if (status != null) {
            final UserStatus s = status;
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), s));
        }
        if (search != null && !search.isBlank()) {
            final String trimmed = search.trim();
            final String like = "%" + trimmed.toLowerCase() + "%";
            final Long asId = parseLongOrNull(trimmed);
            spec = spec.and((root, q, cb) -> {
                var emailPred = cb.like(cb.lower(root.get("email")), like);
                var namePred = cb.like(cb.lower(root.get("displayName")), like);
                var phonePred = cb.like(cb.lower(cb.coalesce(root.get("phone"), "")), like);
                if (asId != null) {
                    return cb.or(emailPred, namePred, phonePred, cb.equal(root.get("id"), asId));
                }
                return cb.or(emailPred, namePred, phonePred);
            });
        }

        Page<User> result = userRepository.findAll(spec, pageable);
        var items = result.getContent().stream().map(AdminUserDto::from).toList();
        return ResponseEntity.ok(PagedResponse.<AdminUserDto>builder()
                .items(items)
                .page(result.getNumber())
                .size(result.getSize())
                .totalItems(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .hasNext(result.hasNext())
                .hasPrevious(result.hasPrevious())
                .build());
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<AdminUserDto> get(@PathVariable Long id) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return ResponseEntity.ok(AdminUserDto.from(u));
    }

    @PostMapping("/{id}/ban")
    public ResponseEntity<AdminUserDto> ban(
            @PathVariable Long id,
            @AuthenticationPrincipal User admin,
            @Valid @RequestBody AdminDecisionRequest request,
            HttpServletRequest httpRequest
    ) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        UserStatus prev = u.getStatus();
        u.setStatus(UserStatus.BANNED);
        userRepository.save(u);

        writeAuditLog(admin, AdminActionType.USER_BANNED, u.getId(), request.getReason(),
                prev, UserStatus.BANNED, httpRequest);

        return ResponseEntity.ok(AdminUserDto.from(u));
    }

    @PostMapping("/{id}/unban")
    public ResponseEntity<AdminUserDto> unban(
            @PathVariable Long id,
            @AuthenticationPrincipal User admin,
            @Valid @RequestBody AdminDecisionRequest request,
            HttpServletRequest httpRequest
    ) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        UserStatus prev = u.getStatus();
        u.setStatus(UserStatus.ACTIVE);
        userRepository.save(u);

        writeAuditLog(admin, AdminActionType.USER_UNBANNED, u.getId(), request.getReason(),
                prev, UserStatus.ACTIVE, httpRequest);

        return ResponseEntity.ok(AdminUserDto.from(u));
    }

    private void writeAuditLog(
            User admin,
            AdminActionType type,
            Long targetId,
            String reason,
            UserStatus oldStatus,
            UserStatus newStatus,
            HttpServletRequest httpRequest
    ) {
        ObjectNode oldState = objectMapper.createObjectNode();
        if (oldStatus != null) oldState.put("status", oldStatus.name());
        ObjectNode newState = objectMapper.createObjectNode();
        newState.put("status", newStatus.name());

        adminActionLogRepository.save(
                AdminActionLog.builder()
                        .eventId(UUID.randomUUID())
                        .adminUser(admin)
                        .actionType(type)
                        .entityType("USER")
                        .entityId(targetId)
                        .reason(reason)
                        .oldState(oldState)
                        .newState(newState)
                        .ipAddress(httpRequest != null ? httpRequest.getRemoteAddr() : null)
                        .userAgent(httpRequest != null ? httpRequest.getHeader("User-Agent") : null)
                        .build()
        );
    }

    private static Long parseLongOrNull(String s) {
        if (s == null) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
