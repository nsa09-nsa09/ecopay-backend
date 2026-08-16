package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kz.hrms.splitupauth.dto.AdminServiceReviewDto;
import kz.hrms.splitupauth.dto.AdminUpdateServiceReviewRequest;
import kz.hrms.splitupauth.dto.CreateServiceReviewRequest;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.dto.PublicServiceReviewDto;
import kz.hrms.splitupauth.dto.ServiceReviewDto;
import kz.hrms.splitupauth.dto.UpdateServiceReviewRequest;
import kz.hrms.splitupauth.entity.AdminActionLog;
import kz.hrms.splitupauth.entity.AdminActionType;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.PaymentTransactionStatus;
import kz.hrms.splitupauth.entity.PaymentTransactionType;
import kz.hrms.splitupauth.entity.ServiceReview;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceConflictException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.PaymentTransactionRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.ServiceReviewRepository;
import kz.hrms.splitupauth.util.TextSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ServiceReviewService {

  private static final int MAX_HOMEPAGE_TESTIMONIALS = 6;

  private final ServiceReviewRepository repository;
  private final PaymentTransactionRepository paymentTransactionRepository;
  private final RoomMemberRepository roomMemberRepository;
  private final AdminActionLogRepository adminActionLogRepository;
  private final ObjectMapper objectMapper;

  @Transactional(readOnly = true)
  public List<PublicServiceReviewDto> getFeatured() {
    return repository.findByFeaturedTrueAndFeaturedPositionIsNotNullOrderByFeaturedPositionAsc()
        .stream()
        .filter(review -> hasVerifiedExperience(review.getAuthor().getId()))
        .limit(MAX_HOMEPAGE_TESTIMONIALS)
        .map(this::toPublicDto)
        .toList();
  }

  @Transactional(readOnly = true)
  public Optional<ServiceReviewDto> getMine(User author) {
    return repository.findByAuthor(author).map(this::toDto);
  }

  @Transactional
  public ServiceReviewDto createMine(User author, CreateServiceReviewRequest req) {
    if (repository.existsByAuthor(author)) {
      throw new ResourceConflictException("You have already submitted a service review");
    }

    ServiceReview review =
        ServiceReview.builder()
            .author(author)
            .rating(req.getRating())
            .text(TextSanitizer.sanitize(req.getText()))
            .featured(false)
            .featuredPosition(null)
            .build();
    review = repository.save(review);
    return toDto(review);
  }

  @Transactional
  public ServiceReviewDto updateMine(User author, UpdateServiceReviewRequest req) {
    ServiceReview review =
        repository
            .findByAuthor(author)
            .orElseThrow(() -> new ResourceNotFoundException("Service review not found"));

    review.setRating(req.getRating());
    review.setText(TextSanitizer.sanitize(req.getText()));
    review.setFeatured(false);
    review.setFeaturedPosition(null);
    review = repository.save(review);
    return toDto(review);
  }

  @Transactional
  public void deleteMine(User author) {
    repository
        .findByAuthor(author)
        .ifPresent(
            review -> {
              review.setFeatured(false);
              review.setFeaturedPosition(null);
              repository.delete(review);
            });
  }

  @Transactional(readOnly = true)
  public PagedResponse<AdminServiceReviewDto> listForAdmin(int page, int size, Boolean featured) {
    if (page < 0) page = 0;
    if (size <= 0 || size > 100) size = 20;
    Pageable pageable = PageRequest.of(page, size);

    Page<ServiceReview> result =
        featured == null
            ? repository.findAllByOrderByCreatedAtDesc(pageable)
            : repository.findByFeaturedOrderByCreatedAtDesc(featured, pageable);

    List<AdminServiceReviewDto> items = result.getContent().stream().map(this::toAdminDto).toList();

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
  public AdminServiceReviewDto setFeatured(
      Long id, boolean featured, Integer homepagePosition, User admin, HttpServletRequest http) {
    ServiceReview review =
        repository
            .findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Service review not found"));

    boolean previousFeatured = Boolean.TRUE.equals(review.getFeatured());
    Integer previousPosition = review.getFeaturedPosition();
    if (featured) {
      validateHomepagePosition(homepagePosition);
      if (!hasVerifiedExperience(review.getAuthor().getId())) {
        throw new InvalidRequestException("Only verified EcoPay users can be featured");
      }
      replaceOccupant(homepagePosition, review.getId(), admin, http);
    }

    review.setFeatured(featured);
    review.setFeaturedPosition(featured ? homepagePosition : null);
    review = repository.save(review);

    ObjectNode oldState = objectMapper.createObjectNode();
    oldState.put("featured", previousFeatured);
    putNullableInt(oldState, "homepagePosition", previousPosition);
    ObjectNode newState = objectMapper.createObjectNode();
    newState.put("featured", featured);
    putNullableInt(newState, "homepagePosition", review.getFeaturedPosition());

    AdminActionType actionType =
        !featured
            ? AdminActionType.TESTIMONIAL_UNFEATURED
            : previousFeatured && !Objects.equals(previousPosition, review.getFeaturedPosition())
                ? AdminActionType.TESTIMONIAL_REORDERED
                : AdminActionType.TESTIMONIAL_FEATURED;
    writeLog(admin, actionType, review.getId(), oldState, newState, http);

    return toAdminDto(review);
  }

  @Transactional
  public AdminServiceReviewDto adminUpdate(
      Long id, AdminUpdateServiceReviewRequest req, User admin, HttpServletRequest http) {
    if (req.getRating() != null) {
      throw new InvalidRequestException("Admin cannot edit testimonial rating");
    }
    if (req.getText() != null && !req.getText().isBlank()) {
      throw new InvalidRequestException("Admin cannot edit testimonial text");
    }
    return repository
        .findById(id)
        .map(this::toAdminDto)
        .orElseThrow(() -> new ResourceNotFoundException("Service review not found"));
  }

  @Transactional
  public void adminDelete(Long id, User admin, HttpServletRequest http) {
    ServiceReview review =
        repository
            .findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Service review not found"));

    ObjectNode oldState = objectMapper.createObjectNode();
    oldState.put("rating", review.getRating());
    oldState.put("featured", review.getFeatured());
    putNullableInt(oldState, "homepagePosition", review.getFeaturedPosition());

    Long reviewId = review.getId();
    review.setFeatured(false);
    review.setFeaturedPosition(null);
    repository.delete(review);

    writeLog(admin, AdminActionType.TESTIMONIAL_DELETED, reviewId, oldState, null, http);
  }

  private void replaceOccupant(
      Integer homepagePosition, Long targetReviewId, User admin, HttpServletRequest http) {
    repository
        .findByFeaturedPosition(homepagePosition)
        .filter(other -> !other.getId().equals(targetReviewId))
        .ifPresent(
            other -> {
              ObjectNode oldState = objectMapper.createObjectNode();
              oldState.put("featured", other.getFeatured());
              putNullableInt(oldState, "homepagePosition", other.getFeaturedPosition());

              other.setFeatured(false);
              other.setFeaturedPosition(null);
              repository.save(other);

              ObjectNode newState = objectMapper.createObjectNode();
              newState.put("featured", false);
              newState.putNull("homepagePosition");
              writeLog(
                  admin,
                  AdminActionType.TESTIMONIAL_UNFEATURED,
                  other.getId(),
                  oldState,
                  newState,
                  http);
            });
  }

  private void validateHomepagePosition(Integer homepagePosition) {
    if (homepagePosition == null
        || homepagePosition < 1
        || homepagePosition > MAX_HOMEPAGE_TESTIMONIALS) {
      throw new InvalidRequestException("Homepage position must be between 1 and 6");
    }
  }

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
        .homepagePosition(review.getFeaturedPosition())
        .verifiedExperience(hasVerifiedExperience(author.getId()))
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
        .homepagePosition(review.getFeaturedPosition())
        .verifiedExperience(true)
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
        .homepagePosition(review.getFeaturedPosition())
        .verifiedExperience(hasVerifiedExperience(author.getId()))
        .createdAt(review.getCreatedAt())
        .updatedAt(review.getUpdatedAt())
        .build();
  }

  private boolean hasVerifiedExperience(Long userId) {
    return paymentTransactionRepository.existsByPaymentIntent_User_IdAndTypeAndStatus(
            userId, PaymentTransactionType.CHARGE, PaymentTransactionStatus.SUCCESS)
        || roomMemberRepository.existsByUser_IdAndDeletedAtIsNullAndStatusIn(
            userId, List.of(MemberStatus.PENDING, MemberStatus.ACTIVE));
  }

  private void writeLog(
      User admin,
      AdminActionType type,
      Long reviewId,
      ObjectNode oldState,
      ObjectNode newState,
      HttpServletRequest http) {
    adminActionLogRepository.save(
        AdminActionLog.builder()
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

  private void putNullableInt(ObjectNode node, String fieldName, Integer value) {
    if (value == null) {
      node.putNull(fieldName);
    } else {
      node.put(fieldName, value);
    }
  }
}
