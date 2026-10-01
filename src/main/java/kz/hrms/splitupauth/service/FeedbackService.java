package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kz.hrms.splitupauth.dto.AdminFeedbackDto;
import kz.hrms.splitupauth.dto.CreateFeedbackRequest;
import kz.hrms.splitupauth.dto.FeedbackDto;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.dto.UpdateFeedbackRequest;
import kz.hrms.splitupauth.entity.AdminActionLog;
import kz.hrms.splitupauth.entity.AdminActionType;
import kz.hrms.splitupauth.entity.Feedback;
import kz.hrms.splitupauth.entity.FeedbackStatus;
import kz.hrms.splitupauth.entity.FeedbackType;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.exception.TooManyLoginAttemptsException;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.FeedbackRepository;
import kz.hrms.splitupauth.util.TextSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User-submitted feedback (complaints / ideas / requests). Owns:
 *
 * <ul>
 *   <li>Authenticated user submit + list-mine.
 *   <li>Admin triage list / detail / patch (status + admin_note), each admin write logged to
 *       admin_action_log via {@link AdminActionType}.
 *   <li>Per-user hourly rate-limit configured by {@code app.rate-limit.feedback.max-per-hour}.
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class FeedbackService {

  private static final int MAX_PAGE_SIZE = 100;

  private final FeedbackRepository feedbackRepository;
  private final AdminActionLogRepository adminActionLogRepository;
  private final ObjectMapper objectMapper;
  private final InMemoryRateLimiter rateLimiter;

  @Value("${app.rate-limit.feedback.max-per-hour:5}")
  private int maxPerHour;

  @Value("${app.rate-limit.feedback.ip-max-per-hour:20}")
  private int ipMaxPerHour;

  // ===================== user surface =====================

  /**
   * Back-compat overload (kept for tests + any code that doesn't have an HttpServletRequest handy).
   * The IP-layer limiter only fires when an {@link HttpServletRequest} is supplied — see {@link
   * #submit(User, CreateFeedbackRequest, HttpServletRequest)}.
   */
  @Transactional
  public FeedbackDto submit(User user, CreateFeedbackRequest req) {
    return submit(user, req, null);
  }

  @Transactional
  public FeedbackDto submit(User user, CreateFeedbackRequest req, HttpServletRequest http) {
    if (http != null) {
      checkIpRateLimit(http);
    }
    checkUserRateLimit(user);

    Feedback fb =
        Feedback.builder()
            .user(user)
            .type(req.getType())
            .subject(TextSanitizer.sanitize(req.getSubject()))
            .message(TextSanitizer.sanitize(req.getMessage()))
            .status(FeedbackStatus.NEW)
            .build();
    fb = feedbackRepository.save(fb);
    return FeedbackDto.from(fb);
  }

  @Transactional(readOnly = true)
  public PagedResponse<FeedbackDto> listMine(User user, int page, int size) {
    Pageable pageable = pageable(page, size);
    Page<Feedback> result = feedbackRepository.findByUserOrderByCreatedAtDesc(user, pageable);
    return toPagedResponse(result.map(FeedbackDto::from));
  }

  private void checkUserRateLimit(User user) {
    // Bypass only when the property is set to 0/negative — production envs
    // must keep a positive cap; the application.properties default is 5.
    if (maxPerHour <= 0) return;
    LocalDateTime hourAgo = LocalDateTime.now().minusHours(1);
    long submittedInWindow = feedbackRepository.countByUserAndCreatedAtAfter(user, hourAgo);
    if (submittedInWindow >= maxPerHour) {
      // Reusing the existing 429 mapping in GlobalExceptionHandler so the
      // client sees the same shape for all rate-limit hits (login, here).
      throw new TooManyLoginAttemptsException(
          "Too many feedback submissions. Please try again later.");
    }
  }

  /**
   * Per-IP layer on top of the per-user limit. One actor cycling through disposable accounts gets
   * stopped here even if each individual account stays under the per-user cap. Uses the in-process
   * limiter (sliding window) — the same component {@code SiteVisitService} uses for visit pings, so
   * the operational story stays consistent.
   */
  private void checkIpRateLimit(HttpServletRequest http) {
    if (ipMaxPerHour <= 0) return;
    String ip = clientIp(http);
    rateLimiter.check(
        "feedback:ip:" + ip,
        ipMaxPerHour,
        3600,
        "Too many feedback submissions from this address. Please try again later.");
  }

  private String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      int comma = forwarded.indexOf(',');
      return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
    }
    return request.getRemoteAddr();
  }

  // ===================== admin surface =====================

  @Transactional(readOnly = true)
  public PagedResponse<AdminFeedbackDto> adminList(
      FeedbackType type, FeedbackStatus status, String q, int page, int size) {
    Specification<Feedback> spec = (root, cq, cb) -> cb.conjunction();
    if (type != null) {
      spec = spec.and((root, cq, cb) -> cb.equal(root.get("type"), type));
    }
    if (status != null) {
      spec = spec.and((root, cq, cb) -> cb.equal(root.get("status"), status));
    }
    if (q != null && !q.isBlank()) {
      String like = "%" + q.trim().toLowerCase() + "%";
      spec =
          spec.and(
              (root, cq, cb) -> {
                Predicate inSubject = cb.like(cb.lower(cb.coalesce(root.get("subject"), "")), like);
                Predicate inMessage = cb.like(cb.lower(root.get("message")), like);
                return cb.or(inSubject, inMessage);
              });
    }

    Pageable pageable =
        PageRequest.of(
            Math.max(0, page), clampSize(size), Sort.by(Sort.Direction.DESC, "createdAt"));
    Page<Feedback> result = feedbackRepository.findAll(spec, pageable);
    return toPagedResponse(result.map(AdminFeedbackDto::from));
  }

  @Transactional(readOnly = true)
  public AdminFeedbackDto adminGet(Long id) {
    Feedback fb =
        feedbackRepository
            .findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Feedback not found"));
    return AdminFeedbackDto.from(fb);
  }

  @Transactional
  public AdminFeedbackDto adminUpdate(
      User admin, Long id, UpdateFeedbackRequest req, HttpServletRequest http) {
    if (req.getStatus() == null && req.getAdminNote() == null) {
      throw new InvalidRequestException("At least one of {status, adminNote} must be provided");
    }

    Feedback fb =
        feedbackRepository
            .findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Feedback not found"));

    FeedbackStatus prevStatus = fb.getStatus();
    String prevNote = fb.getAdminNote();

    boolean statusChanged = req.getStatus() != null && req.getStatus() != prevStatus;
    boolean noteChanged =
        req.getAdminNote() != null && !Objects.equals(req.getAdminNote(), prevNote);

    if (statusChanged) {
      fb.setStatus(req.getStatus());
    }
    if (req.getAdminNote() != null) {
      fb.setAdminNote(TextSanitizer.sanitize(req.getAdminNote()));
    }
    if (statusChanged || noteChanged) {
      fb.setHandledBy(admin);
    }
    fb = feedbackRepository.save(fb);

    if (statusChanged) {
      ObjectNode oldState = objectMapper.createObjectNode().put("status", prevStatus.name());
      ObjectNode newState = objectMapper.createObjectNode().put("status", fb.getStatus().name());
      writeAuditLog(
          admin, AdminActionType.FEEDBACK_STATUS_CHANGED, fb.getId(), oldState, newState, http);
    }
    if (noteChanged) {
      ObjectNode oldState = objectMapper.createObjectNode().put("adminNote", prevNote);
      ObjectNode newState = objectMapper.createObjectNode().put("adminNote", fb.getAdminNote());
      writeAuditLog(
          admin, AdminActionType.FEEDBACK_NOTE_UPDATED, fb.getId(), oldState, newState, http);
    }

    return AdminFeedbackDto.from(fb);
  }

  // ===================== helpers =====================

  private Pageable pageable(int page, int size) {
    return PageRequest.of(
        Math.max(0, page), clampSize(size), Sort.by(Sort.Direction.DESC, "createdAt"));
  }

  private int clampSize(int size) {
    if (size <= 0) return 20;
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private <T> PagedResponse<T> toPagedResponse(Page<T> page) {
    List<T> items = new ArrayList<>(page.getContent());
    return PagedResponse.<T>builder()
        .items(items)
        .page(page.getNumber())
        .size(page.getSize())
        .totalItems(page.getTotalElements())
        .totalPages(page.getTotalPages())
        .hasNext(page.hasNext())
        .hasPrevious(page.hasPrevious())
        .build();
  }

  private void writeAuditLog(
      User admin,
      AdminActionType type,
      Long feedbackId,
      ObjectNode oldState,
      ObjectNode newState,
      HttpServletRequest http) {
    adminActionLogRepository.save(
        AdminActionLog.builder()
            .eventId(UUID.randomUUID())
            .adminUser(admin)
            .actionType(type)
            .entityType("FEEDBACK")
            .entityId(feedbackId)
            .reason(null)
            .oldState(oldState)
            .newState(newState)
            .ipAddress(http != null ? http.getRemoteAddr() : null)
            .userAgent(http != null ? http.getHeader("User-Agent") : null)
            .build());
  }
}
