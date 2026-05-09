package kz.hrms.splitupauth.controller;

import kz.hrms.splitupauth.dto.AdminUserDto;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<PagedResponse<AdminUserDto>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (page < 0) page = 0;
        if (size <= 0 || size > 100) size = 20;
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<User> spec = (root, q, cb) -> cb.conjunction();
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        if (search != null && !search.isBlank()) {
            String like = "%" + search.toLowerCase() + "%";
            spec = spec.and((root, q, cb) -> cb.or(
                    cb.like(cb.lower(root.get("email")), like),
                    cb.like(cb.lower(root.get("displayName")), like),
                    cb.like(cb.coalesce(root.get("phone"), ""), like)
            ));
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
    public ResponseEntity<AdminUserDto> get(@PathVariable Long id) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return ResponseEntity.ok(AdminUserDto.from(u));
    }

    @PostMapping("/{id}/ban")
    public ResponseEntity<AdminUserDto> ban(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        u.setStatus(UserStatus.BANNED);
        userRepository.save(u);
        return ResponseEntity.ok(AdminUserDto.from(u));
    }

    @PostMapping("/{id}/unban")
    public ResponseEntity<AdminUserDto> unban(@PathVariable Long id) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        u.setStatus(UserStatus.ACTIVE);
        userRepository.save(u);
        return ResponseEntity.ok(AdminUserDto.from(u));
    }
}
