package kz.hrms.splitupauth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.AdminCreateUserRequest;
import kz.hrms.splitupauth.dto.AdminDecisionRequest;
import kz.hrms.splitupauth.dto.AdminUserDto;
import kz.hrms.splitupauth.dto.ChangeUserRoleRequest;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.dto.SetOwnerVerifiedRequest;
import kz.hrms.splitupauth.entity.AdminActionLog;
import kz.hrms.splitupauth.entity.AdminActionType;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.PhoneAlreadyExistsException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.exception.UserAlreadyExistsException;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.DisputeRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.SupportTicketRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.service.TokenRevocationService;
import kz.hrms.splitupauth.websocket.AccountRealtimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final SupportTicketRepository supportTicketRepository;
    private final DisputeRepository disputeRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final TokenRevocationService tokenRevocationService;
    private final AccountRealtimeService accountRealtimeService;

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

        String sortField = (sort == null) ? "createdAt" : SORT_FIELD_MAP.getOrDefault(sort, "createdAt");
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
        // Counters are intentionally zeroed in the list to avoid N+1; the
        // detail GET returns real values.
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
        return ResponseEntity.ok(buildDetailDto(u));
    }

    @PostMapping
    public ResponseEntity<AdminUserDto> create(
            @AuthenticationPrincipal User admin,
            @Valid @RequestBody AdminCreateUserRequest request,
            HttpServletRequest httpRequest
    ) {
        String email = request.getEmail().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new UserAlreadyExistsException("User with this email already exists");
        }

        String phone = request.getPhone();
        if (phone != null && !phone.isBlank()) {
            if (userRepository.existsByPhone(phone)) {
                throw new PhoneAlreadyExistsException("User with this phone already exists");
            }
        } else {
            phone = null;
        }

        User newUser = User.builder()
                .email(email)
                .password(passwordEncoder.encode(request.getPassword()))
                .displayName(request.getDisplayName())
                .phone(phone)
                .role(request.getRole())
                .status(UserStatus.ACTIVE)
                .reputation(0)
                .emailVerified(true)   // created by admin → bypass email verification
                .ownerVerified(false)
                .build();

        newUser = userRepository.save(newUser);

        ObjectNode newState = objectMapper.createObjectNode();
        newState.put("email", newUser.getEmail());
        newState.put("role", newUser.getRole().name());
        adminActionLogRepository.save(
                AdminActionLog.builder()
                        .eventId(UUID.randomUUID())
                        .adminUser(admin)
                        .actionType(AdminActionType.USER_CREATED)
                        .entityType("USER")
                        .entityId(newUser.getId())
                        .reason(null)
                        .oldState(null)
                        .newState(newState)
                        .ipAddress(httpRequest.getRemoteAddr())
                        .userAgent(httpRequest.getHeader("User-Agent"))
                        .build()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(buildDetailDto(newUser));
    }

    @PatchMapping("/{id}/role")
    public ResponseEntity<AdminUserDto> changeRole(
            @PathVariable Long id,
            @AuthenticationPrincipal User admin,
            @Valid @RequestBody ChangeUserRoleRequest request,
            HttpServletRequest httpRequest
    ) {
        // Self-demotion guard: an admin must not be able to drop their own
        // ADMIN role and lock themselves out of the panel.
        if (admin != null && admin.getId() != null && admin.getId().equals(id)) {
            throw new ForbiddenOperationException("Admin cannot change their own role");
        }

        User target = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Role prev = target.getRole();
        Role next = request.getRole();

        target.setRole(next);
        target = userRepository.save(target);

        ObjectNode oldState = objectMapper.createObjectNode();
        oldState.put("role", prev == null ? null : prev.name());
        ObjectNode newState = objectMapper.createObjectNode();
        newState.put("role", next.name());
        adminActionLogRepository.save(
                AdminActionLog.builder()
                        .eventId(UUID.randomUUID())
                        .adminUser(admin)
                        .actionType(AdminActionType.USER_ROLE_CHANGED)
                        .entityType("USER")
                        .entityId(target.getId())
                        .reason(request.getReason())
                        .oldState(oldState)
                        .newState(newState)
                        .ipAddress(httpRequest.getRemoteAddr())
                        .userAgent(httpRequest.getHeader("User-Agent"))
                        .build()
        );

        return ResponseEntity.ok(buildDetailDto(target));
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
        java.time.LocalDateTime bannedAt = java.time.LocalDateTime.now();
        u.setStatus(UserStatus.BANNED);
        u.setBanReason(request.getReason());
        u.setBannedAt(bannedAt);
        userRepository.save(u);

        writeAuditLog(admin, AdminActionType.USER_BANNED, u.getId(), request.getReason(),
                prev, UserStatus.BANNED, httpRequest);

        // Invalidate the active session immediately so refresh-token rotation
        // can't keep the user signed in after the ban.
        tokenRevocationService.revokeAllUserTokens(u);
        // Push the live-ban notification to the user's personal account topic.
        accountRealtimeService.publishBanned(u.getId(), u.getBanReason(), u.getBannedAt());

        return ResponseEntity.ok(buildDetailDto(u));
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
        u.setBanReason(null);
        u.setBannedAt(null);
        userRepository.save(u);

        writeAuditLog(admin, AdminActionType.USER_UNBANNED, u.getId(), request.getReason(),
                prev, UserStatus.ACTIVE, httpRequest);

        accountRealtimeService.publishUnbanned(u.getId());

        return ResponseEntity.ok(buildDetailDto(u));
    }

    @PatchMapping("/{id}/owner-verified")
    public ResponseEntity<AdminUserDto> setOwnerVerified(
            @PathVariable Long id,
            @AuthenticationPrincipal User admin,
            @Valid @RequestBody SetOwnerVerifiedRequest request,
            HttpServletRequest httpRequest
    ) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        boolean prev = Boolean.TRUE.equals(u.getOwnerVerified());
        u.setOwnerVerified(request.getVerified());
        userRepository.save(u);

        ObjectNode oldState = objectMapper.createObjectNode();
        oldState.put("ownerVerified", prev);
        ObjectNode newState = objectMapper.createObjectNode();
        newState.put("ownerVerified", request.getVerified());
        adminActionLogRepository.save(
                AdminActionLog.builder()
                        .eventId(UUID.randomUUID())
                        .adminUser(admin)
                        .actionType(AdminActionType.OWNER_VERIFICATION_CHANGED)
                        .entityType("USER")
                        .entityId(u.getId())
                        .reason(request.getReason())
                        .oldState(oldState)
                        .newState(newState)
                        .ipAddress(httpRequest.getRemoteAddr())
                        .userAgent(httpRequest.getHeader("User-Agent"))
                        .build()
        );

        return ResponseEntity.ok(buildDetailDto(u));
    }

    private AdminUserDto buildDetailDto(User u) {
        long roomsOwned = roomRepository.countByOwnerAndDeletedAtIsNull(u);
        long roomsJoined = roomMemberRepository.countByUserAndDeletedAtIsNull(u);
        long tickets = supportTicketRepository.countByUser(u);
        long disputes = disputeRepository.countByOpenedByUser(u);
        return AdminUserDto.fromWithCounters(u, roomsOwned, roomsJoined, tickets, disputes);
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
