package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import kz.hrms.splitupauth.dto.CreateNewsRequest;
import kz.hrms.splitupauth.dto.NewsDto;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.dto.UpdateNewsRequest;
import kz.hrms.splitupauth.entity.AdminActionLog;
import kz.hrms.splitupauth.entity.AdminActionType;
import kz.hrms.splitupauth.entity.News;
import kz.hrms.splitupauth.entity.NewsStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.NewsRepository;
import kz.hrms.splitupauth.util.TextSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * CRUD for editorial news items. Read paths split into:
 *
 * <ul>
 *   <li>Public feed — only PUBLISHED, sorted by sort_order then published_at desc.</li>
 *   <li>Admin list — every status, newest-first, optional status filter.</li>
 * </ul>
 *
 * Every admin write logs to {@code admin_action_log} via
 * {@link AdminActionType#NEWS_CREATED}/{@code NEWS_UPDATED}/{@code NEWS_DELETED}.
 * Image upload/replace and delete-time cleanup are delegated to
 * {@link NewsImageStorageService}; this service never talks to S3 directly.
 */
@Service
@RequiredArgsConstructor
public class NewsService {

    private static final int MAX_PAGE_SIZE = 50;

    private final NewsRepository newsRepository;
    private final AdminActionLogRepository adminActionLogRepository;
    private final NewsImageStorageService imageStorage;
    private final ObjectMapper objectMapper;

    // ===================== public surface =====================

    @Transactional(readOnly = true)
    public PagedResponse<NewsDto> publicList(int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), clampSize(size));
        Page<News> result = newsRepository
                .findByStatusOrderBySortOrderAscPublishedAtDesc(NewsStatus.PUBLISHED, pageable);
        return toPagedResponse(result.map(this::toDto));
    }

    @Transactional(readOnly = true)
    public NewsDto publicGet(Long id) {
        News news = newsRepository.findById(id)
                .filter(n -> n.getStatus() == NewsStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("News not found"));
        return toDto(news);
    }

    // ===================== admin surface =====================

    @Transactional(readOnly = true)
    public PagedResponse<NewsDto> adminList(NewsStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), clampSize(size));
        Page<News> result = (status != null)
                ? newsRepository.findByStatusOrderByCreatedAtDesc(status, pageable)
                : newsRepository.findAllByOrderByCreatedAtDesc(pageable);
        return toPagedResponse(result.map(this::toDto));
    }

    @Transactional(readOnly = true)
    public NewsDto adminGet(Long id) {
        News news = newsRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("News not found"));
        return toDto(news);
    }

    @Transactional
    public NewsDto create(User admin, CreateNewsRequest req, HttpServletRequest http) {
        NewsStatus status = req.getStatus() != null ? req.getStatus() : NewsStatus.DRAFT;

        News news = News.builder()
                .titleKz(TextSanitizer.sanitize(req.getTitleKz()))
                .titleRu(TextSanitizer.sanitize(req.getTitleRu()))
                .titleEn(TextSanitizer.sanitize(req.getTitleEn()))
                .bodyKz(TextSanitizer.sanitize(req.getBodyKz()))
                .bodyRu(TextSanitizer.sanitize(req.getBodyRu()))
                .bodyEn(TextSanitizer.sanitize(req.getBodyEn()))
                .status(status)
                .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
                .createdBy(admin)
                .build();
        if (status == NewsStatus.PUBLISHED) {
            news.setPublishedAt(LocalDateTime.now());
        }
        requireAnyTitle(news);
        news = newsRepository.save(news);

        ObjectNode newState = snapshot(news);
        writeLog(admin, AdminActionType.NEWS_CREATED, news.getId(), null, newState, http);

        return toDto(news);
    }

    @Transactional
    public NewsDto update(Long id, User admin, UpdateNewsRequest req, HttpServletRequest http) {
        News news = newsRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("News not found"));

        ObjectNode oldState = snapshot(news);
        NewsStatus prevStatus = news.getStatus();

        if (req.getTitleKz() != null) news.setTitleKz(TextSanitizer.sanitize(req.getTitleKz()));
        if (req.getTitleRu() != null) news.setTitleRu(TextSanitizer.sanitize(req.getTitleRu()));
        if (req.getTitleEn() != null) news.setTitleEn(TextSanitizer.sanitize(req.getTitleEn()));
        if (req.getBodyKz() != null)  news.setBodyKz(TextSanitizer.sanitize(req.getBodyKz()));
        if (req.getBodyRu() != null)  news.setBodyRu(TextSanitizer.sanitize(req.getBodyRu()));
        if (req.getBodyEn() != null)  news.setBodyEn(TextSanitizer.sanitize(req.getBodyEn()));
        if (req.getSortOrder() != null) news.setSortOrder(req.getSortOrder());

        if (req.getStatus() != null && req.getStatus() != prevStatus) {
            news.setStatus(req.getStatus());
            if (req.getStatus() == NewsStatus.PUBLISHED && news.getPublishedAt() == null) {
                news.setPublishedAt(LocalDateTime.now());
            }
        }

        requireAnyTitle(news);
        news = newsRepository.save(news);

        ObjectNode newState = snapshot(news);
        writeLog(admin, AdminActionType.NEWS_UPDATED, news.getId(), oldState, newState, http);

        return toDto(news);
    }

    /**
     * Hard delete + remove the attached S3 object if any. We pick hard delete
     * over a soft "ARCHIVED" status because archive already exists as an
     * editorial state — making delete also soft would leave admins with no
     * way to actually remove an item. The audit row keeps the trail.
     */
    @Transactional
    public void delete(Long id, User admin, HttpServletRequest http) {
        News news = newsRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("News not found"));

        ObjectNode oldState = snapshot(news);

        String imageKey = news.getImageKey();
        newsRepository.delete(news);
        // S3 delete after the JPA delete commits — best-effort; if it fails the
        // logged warn is enough to track. Doing it inside the same tx keeps
        // the call simple at the cost of a tiny window where a rolled-back
        // tx could leave an orphaned key (which is fine: harmless and rare).
        imageStorage.deleteIfManaged(imageKey);

        writeLog(admin, AdminActionType.NEWS_DELETED, id, oldState, null, http);
    }

    @Transactional
    public NewsDto uploadImage(Long id, User admin, MultipartFile file, HttpServletRequest http) {
        News news = newsRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("News not found"));

        String oldKey = news.getImageKey();
        String newKey = imageStorage.store(file);
        news.setImageKey(newKey);
        news = newsRepository.save(news);

        // Replace = remove the previous object so the bucket doesn't accumulate
        // orphans. Best-effort; failure is logged inside the storage service.
        if (oldKey != null && !oldKey.equals(newKey)) {
            imageStorage.deleteIfManaged(oldKey);
        }

        ObjectNode oldState = objectMapper.createObjectNode();
        oldState.put("imageKey", oldKey);
        ObjectNode newState = objectMapper.createObjectNode();
        newState.put("imageKey", news.getImageKey());
        writeLog(admin, AdminActionType.NEWS_UPDATED, news.getId(), oldState, newState, http);

        return toDto(news);
    }

    // ===================== helpers =====================

    private void requireAnyTitle(News news) {
        boolean hasTitle = isNonBlank(news.getTitleKz())
                || isNonBlank(news.getTitleRu())
                || isNonBlank(news.getTitleEn());
        if (!hasTitle) {
            throw new InvalidRequestException(
                    "Хотя бы один заголовок (kz/ru/en) должен быть заполнен");
        }
    }

    private boolean isNonBlank(String s) {
        return s != null && !s.isBlank();
    }

    private int clampSize(int size) {
        if (size <= 0) return 20;
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private NewsDto toDto(News n) {
        return NewsDto.builder()
                .id(n.getId())
                .titleKz(n.getTitleKz())
                .titleRu(n.getTitleRu())
                .titleEn(n.getTitleEn())
                .bodyKz(n.getBodyKz())
                .bodyRu(n.getBodyRu())
                .bodyEn(n.getBodyEn())
                .imageUrl(imageStorage.publicUrl(n.getImageKey()))
                .status(n.getStatus())
                .publishedAt(n.getPublishedAt())
                .sortOrder(n.getSortOrder())
                .createdAt(n.getCreatedAt())
                .updatedAt(n.getUpdatedAt())
                .build();
    }

    private ObjectNode snapshot(News n) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("titleKz", n.getTitleKz());
        node.put("titleRu", n.getTitleRu());
        node.put("titleEn", n.getTitleEn());
        node.put("bodyKz", n.getBodyKz());
        node.put("bodyRu", n.getBodyRu());
        node.put("bodyEn", n.getBodyEn());
        node.put("imageKey", n.getImageKey());
        node.put("status", n.getStatus() != null ? n.getStatus().name() : null);
        node.put("sortOrder", n.getSortOrder());
        return node;
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

    private void writeLog(User admin,
                          AdminActionType type,
                          Long entityId,
                          ObjectNode oldState,
                          ObjectNode newState,
                          HttpServletRequest http) {
        adminActionLogRepository.save(AdminActionLog.builder()
                .eventId(UUID.randomUUID())
                .adminUser(admin)
                .actionType(type)
                .entityType("NEWS")
                .entityId(entityId)
                .reason(null)
                .oldState(oldState)
                .newState(newState)
                .ipAddress(http != null ? http.getRemoteAddr() : null)
                .userAgent(http != null ? http.getHeader("User-Agent") : null)
                .build());
    }
}
