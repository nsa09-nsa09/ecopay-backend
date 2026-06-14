package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import kz.hrms.splitupauth.dto.AdminServiceReviewDto;
import kz.hrms.splitupauth.dto.AdminUpdateServiceReviewRequest;
import kz.hrms.splitupauth.dto.CreateServiceReviewRequest;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.dto.PublicServiceReviewDto;
import kz.hrms.splitupauth.dto.ServiceReviewDto;
import kz.hrms.splitupauth.dto.UpdateServiceReviewRequest;
import kz.hrms.splitupauth.entity.AdminActionLog;
import kz.hrms.splitupauth.entity.AdminActionType;
import kz.hrms.splitupauth.entity.ServiceReview;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.ResourceConflictException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.ServiceReviewRepository;
import kz.hrms.splitupauth.util.TextSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ServiceReviewService {

    private final ServiceReviewRepository repository;
    private final AdminActionLogRepository adminActionLogRepository;
    private final ObjectMapper objectMapper;

    // ===================== Public =====================

    @Transactional(readOnly = true)
    public List<PublicServiceReviewDto> getFeatured() {
        return repository.findTop30ByFeaturedTrueOrderByCreatedAtDesc().stream()
                .map(this::toPublicDto)
                .toList();
    }

    // ===================== Author (me) =====================

    @Transactional(readOnly = true)
    public Optional<ServiceReviewDto> getMine(User author) {
        return repository.findByAuthor(author).map(this::toDto);
    }

    @Transactional
    public ServiceReviewDto createMine(User author, CreateServiceReviewRequest req) {
        if (repository.existsByAuthor(author)) {
            throw new ResourceConflictException("Вы уже оставляли отзыв");
        }

        ServiceReview review = ServiceReview.builder()
                .author(author)
                .rating(req.getRating())
                .text(TextSanitizer.sanitize(req.getText()))
                .featured(false)
                .build();
        review = repository.save(review);
        return toDto(review);
    }

    @Transactional
    public ServiceReviewDto updateMine(User author, UpdateServiceReviewRequest req) {
        ServiceReview review = repository.findByAuthor(author)
                .orElseThrow(() -> new ResourceNotFoundException("Отзыв не найден"));

        review.setRating(req.getRating());
        review.setText(TextSanitizer.sanitize(req.getText()));
        // Edits force re-moderation per contract: a featured review whose text
        // changed must be re-approved by an admin before showing on the homepage.
        review.setFeatured(false);
        review = repository.save(review);
        return toDto(review);
    }

    @Transactional
    public void deleteMine(User author) {
        repository.findByAuthor(author).ifPresent(repository::delete);
    }

    // ===================== Admin =====================

    @Transactional(readOnly = true)
    public PagedResponse<AdminServiceReviewDto> listForAdmin(int page, int size, Boolean featured) {
        if (page < 0) page = 0;
        if (size <= 0 || size > 100) size = 20;
        Pageable pageable = PageRequest.of(page, size);

        Page<ServiceReview> result = featured == null
                ? repository.findAllByOrderByCreatedAtDesc(pageable)
                : repository.findByFeaturedOrderByCreatedAtDesc(featured, pageable);

        List<AdminServiceReviewDto> items = result.getContent().stream()
                .map(this::toAdminDto).toList();

        return PagedResponse.<AdminServiceReviewDto>builder()
                .items(items)
                .page(result.getNumber())
                .size(result.getSize())
                .totalItems(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .hasNext(result.hasNext())
                .hasPrevious(result.hasPrevious())
                .build();
    }

    @Transactional
    public AdminServiceReviewDto setFeatured(Long id, boolean featured, User admin, HttpServletRequest http) {
        ServiceReview review = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Отзыв не найден"));

        boolean prev = Boolean.TRUE.equals(review.getFeatured());
        review.setFeatured(featured);
        review = repository.save(review);

        ObjectNode oldState = objectMapper.createObjectNode();
        oldState.put("featured", prev);
        ObjectNode newState = objectMapper.createObjectNode();
        newState.put("featured", featured);
        writeLog(admin, featured ? AdminActionType.TESTIMONIAL_FEATURED
                                 : AdminActionType.TESTIMONIAL_UNFEATURED,
                review.getId(), oldState, newState, http);

        return toAdminDto(review);
    }

    @Transactional
    public AdminServiceReviewDto adminUpdate(Long id, AdminUpdateServiceReviewRequest req, User admin, HttpServletRequest http) {
        ServiceReview review = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Отзыв не найден"));

        ObjectNode oldState = objectMapper.createObjectNode();
        oldState.put("rating", review.getRating());
        oldState.put("text", review.getText());

        if (req.getRating() != null) {
            review.setRating(req.getRating());
        }
        if (req.getText() != null && !req.getText().isBlank()) {
            review.setText(TextSanitizer.sanitize(req.getText()));
        }
        review = repository.save(review);

        ObjectNode newState = objectMapper.createObjectNode();
        newState.put("rating", review.getRating());
        newState.put("text", review.getText());
        writeLog(admin, AdminActionType.TESTIMONIAL_EDITED, review.getId(), oldState, newState, http);

        return toAdminDto(review);
    }

    @Transactional
    public void adminDelete(Long id, User admin, HttpServletRequest http) {
        ServiceReview review = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Отзыв не найден"));

        ObjectNode oldState = objectMapper.createObjectNode();
        oldState.put("rating", review.getRating());
        oldState.put("featured", review.getFeatured());

        Long reviewId = review.getId();
        repository.delete(review);

        writeLog(admin, AdminActionType.TESTIMONIAL_DELETED, reviewId, oldState, null, http);
    }

    // ===================== Mapping =====================

    private ServiceReviewDto toDto(ServiceReview review) {
        User author = review.getAuthor();
        return ServiceReviewDto.builder()
                .id(review.getId())
                .authorId(author.getId())
                .authorDisplayName(author.getDisplayName())
                .authorPublicId(author.getPublicId())
                .rating(review.getRating())
                .text(review.getText())
                .featured(review.getFeatured())
                .createdAt(review.getCreatedAt())
                .updatedAt(review.getUpdatedAt())
                .build();
    }

    private PublicServiceReviewDto toPublicDto(ServiceReview review) {
        User author = review.getAuthor();
        return PublicServiceReviewDto.builder()
                .id(review.getId())
                .rating(review.getRating())
                .text(review.getText())
                .authorDisplayName(author.getDisplayName())
                .authorPublicId(author.getPublicId())
                .createdAt(review.getCreatedAt())
                .build();
    }

    private AdminServiceReviewDto toAdminDto(ServiceReview review) {
        User author = review.getAuthor();
        return AdminServiceReviewDto.builder()
                .id(review.getId())
                .authorId(author.getId())
                .authorPublicId(author.getPublicId())
                .authorDisplayName(author.getDisplayName())
                .authorEmail(author.getEmail())
                .rating(review.getRating())
                .text(review.getText())
                .featured(review.getFeatured())
                .createdAt(review.getCreatedAt())
                .updatedAt(review.getUpdatedAt())
                .build();
    }

    private void writeLog(User admin, AdminActionType type, Long reviewId,
                          ObjectNode oldState, ObjectNode newState,
                          HttpServletRequest http) {
        adminActionLogRepository.save(AdminActionLog.builder()
                .eventId(UUID.randomUUID())
                .adminUser(admin)
                .actionType(type)
                .entityType("SERVICE_REVIEW")
                .entityId(reviewId)
                .oldState(oldState)
                .newState(newState)
                .ipAddress(http != null ? http.getRemoteAddr() : null)
                .userAgent(http != null ? http.getHeader("User-Agent") : null)
                .build());
    }
}
