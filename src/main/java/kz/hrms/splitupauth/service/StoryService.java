package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import kz.hrms.splitupauth.dto.CreateStoryRequest;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.dto.StoryDto;
import kz.hrms.splitupauth.dto.UpdateStoryRequest;
import kz.hrms.splitupauth.entity.AdminActionType;
import kz.hrms.splitupauth.entity.Story;
import kz.hrms.splitupauth.entity.StoryStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.StoryRepository;
import kz.hrms.splitupauth.util.TextSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class StoryService {

  private static final int MAX_PAGE_SIZE = 50;

  private final StoryRepository storyRepository;
  private final StoryAuditWriter auditWriter;
  private final StoryImageStorageService imageStorage;
  private final ObjectMapper objectMapper;

  @Transactional(readOnly = true)
  public PagedResponse<StoryDto> publicList(int page, int size) {
    Pageable pageable = PageRequest.of(Math.max(0, page), clampSize(size));
    Page<Story> result =
        storyRepository.findByStatusOrderBySortOrderAscPublishedAtDesc(
            StoryStatus.PUBLISHED, pageable);
    return toPagedResponse(result.map(this::toDto));
  }

  @Transactional(readOnly = true)
  public PagedResponse<StoryDto> adminList(StoryStatus status, int page, int size) {
    Pageable pageable = PageRequest.of(Math.max(0, page), clampSize(size));
    Page<Story> result =
        status != null
            ? storyRepository.findByStatusOrderByCreatedAtDesc(status, pageable)
            : storyRepository.findAllByOrderByCreatedAtDesc(pageable);
    return toPagedResponse(result.map(this::toDto));
  }

  @Transactional(readOnly = true)
  public StoryDto adminGet(Long id) {
    return toDto(find(id));
  }

  @Transactional
  public StoryDto create(User admin, CreateStoryRequest req, HttpServletRequest http) {
    StoryStatus status = req.getStatus() != null ? req.getStatus() : StoryStatus.DRAFT;
    Story story =
        Story.builder()
            .titleKz(clean(req.getTitleKz()))
            .titleRu(clean(req.getTitleRu()))
            .titleEn(clean(req.getTitleEn()))
            .headingKz(clean(req.getHeadingKz()))
            .headingRu(clean(req.getHeadingRu()))
            .headingEn(clean(req.getHeadingEn()))
            .bodyKz(clean(req.getBodyKz()))
            .bodyRu(clean(req.getBodyRu()))
            .bodyEn(clean(req.getBodyEn()))
            .ctaLabelKz(clean(req.getCtaLabelKz()))
            .ctaLabelRu(clean(req.getCtaLabelRu()))
            .ctaLabelEn(clean(req.getCtaLabelEn()))
            .ctaUrl(clean(req.getCtaUrl()))
            .emoji(clean(req.getEmoji()))
            .gradient(clean(req.getGradient()))
            .status(status)
            .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
            .createdBy(admin)
            .build();
    if (status == StoryStatus.PUBLISHED) {
      story.setPublishedAt(LocalDateTime.now());
    }
    requireAnyTitle(story);
    story = storyRepository.save(story);
    auditWriter.writeOrSwallow(
        admin, AdminActionType.STORY_CREATED, story.getId(), null, snapshot(story), http);
    return toDto(story);
  }

  @Transactional
  public StoryDto update(Long id, User admin, UpdateStoryRequest req, HttpServletRequest http) {
    Story story = find(id);
    ObjectNode oldState = snapshot(story);
    StoryStatus prevStatus = story.getStatus();

    apply(story, req);

    if (req.getStatus() != null && req.getStatus() != prevStatus) {
      story.setStatus(req.getStatus());
      if (req.getStatus() == StoryStatus.PUBLISHED && story.getPublishedAt() == null) {
        story.setPublishedAt(LocalDateTime.now());
      }
    }

    requireAnyTitle(story);
    story = storyRepository.save(story);
    auditWriter.writeOrSwallow(
        admin, AdminActionType.STORY_UPDATED, story.getId(), oldState, snapshot(story), http);
    return toDto(story);
  }

  @Transactional
  public void delete(Long id, User admin, HttpServletRequest http) {
    Story story = find(id);
    ObjectNode oldState = snapshot(story);
    storyRepository.delete(story);
    imageKeys(story).distinct().forEach(imageStorage::deleteIfManaged);
    auditWriter.writeOrSwallow(admin, AdminActionType.STORY_DELETED, id, oldState, null, http);
  }

  @Transactional
  public StoryDto uploadImage(Long id, User admin, MultipartFile file, HttpServletRequest http) {
    Story story = find(id);
    String oldKey = story.getImageKey();
    String newKey = imageStorage.store(file);
    story.setImageKey(newKey);
    story = storyRepository.save(story);
    deleteIfUnreferenced(oldKey, story);

    ObjectNode oldState = objectMapper.createObjectNode();
    oldState.put("imageKey", oldKey);
    ObjectNode newState = objectMapper.createObjectNode();
    newState.put("imageKey", story.getImageKey());
    auditWriter.writeOrSwallow(
        admin, AdminActionType.STORY_UPDATED, story.getId(), oldState, newState, http);
    return toDto(story);
  }

  @Transactional
  public StoryDto deleteImage(Long id, User admin, HttpServletRequest http) {
    Story story = find(id);
    String oldKey = story.getImageKey();
    if (oldKey == null || oldKey.isBlank()) {
      return toDto(story);
    }
    story.setImageKey(null);
    story = storyRepository.save(story);
    deleteIfUnreferenced(oldKey, story);

    ObjectNode oldState = objectMapper.createObjectNode();
    oldState.put("imageKey", oldKey);
    ObjectNode newState = objectMapper.createObjectNode();
    newState.putNull("imageKey");
    auditWriter.writeOrSwallow(
        admin, AdminActionType.STORY_UPDATED, story.getId(), oldState, newState, http);
    return toDto(story);
  }

  @Transactional
  public StoryDto uploadImage(
      Long id, String locale, User admin, MultipartFile file, HttpServletRequest http) {
    String field = imageField(locale);
    Story story = find(id);
    String oldKey = localizedKey(story, locale);
    String newKey = imageStorage.store(file);
    setLocalizedKey(story, locale, newKey);
    story = storyRepository.save(story);
    deleteIfUnreferenced(oldKey, story);
    auditImageChange(admin, story.getId(), field, oldKey, newKey, http);
    return toDto(story);
  }

  @Transactional
  public StoryDto deleteImage(Long id, String locale, User admin, HttpServletRequest http) {
    String field = imageField(locale);
    Story story = find(id);
    String oldKey = localizedKey(story, locale);
    if (oldKey == null || oldKey.isBlank()) {
      return toDto(story);
    }
    setLocalizedKey(story, locale, null);
    story = storyRepository.save(story);
    deleteIfUnreferenced(oldKey, story);
    auditImageChange(admin, story.getId(), field, oldKey, null, http);
    return toDto(story);
  }

  private String imageField(String locale) {
    if ("kz".equals(locale)) return "imageKeyKz";
    if ("ru".equals(locale)) return "imageKeyRu";
    if ("en".equals(locale)) return "imageKeyEn";
    throw new InvalidRequestException("Unsupported image locale");
  }

  private String localizedKey(Story story, String locale) {
    return switch (locale) {
      case "kz" -> story.getImageKeyKz();
      case "ru" -> story.getImageKeyRu();
      case "en" -> story.getImageKeyEn();
      default -> throw new InvalidRequestException("Unsupported image locale");
    };
  }

  private void setLocalizedKey(Story story, String locale, String key) {
    switch (locale) {
      case "kz" -> story.setImageKeyKz(key);
      case "ru" -> story.setImageKeyRu(key);
      case "en" -> story.setImageKeyEn(key);
      default -> throw new InvalidRequestException("Unsupported image locale");
    }
  }

  private Stream<String> imageKeys(Story story) {
    return Stream.of(
            story.getImageKey(),
            story.getImageKeyKz(),
            story.getImageKeyRu(),
            story.getImageKeyEn())
        .filter(Objects::nonNull);
  }

  private void deleteIfUnreferenced(String key, Story story) {
    if (key != null && imageKeys(story).noneMatch(key::equals)) {
      imageStorage.deleteIfManaged(key);
    }
  }

  private void auditImageChange(
      User admin, Long id, String field, String oldKey, String newKey, HttpServletRequest http) {
    ObjectNode oldState = objectMapper.createObjectNode();
    oldState.put(field, oldKey);
    ObjectNode newState = objectMapper.createObjectNode();
    newState.put(field, newKey);
    auditWriter.writeOrSwallow(admin, AdminActionType.STORY_UPDATED, id, oldState, newState, http);
  }

  private Story find(Long id) {
    return storyRepository
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Story not found"));
  }

  private void apply(Story story, UpdateStoryRequest req) {
    if (req.getTitleKz() != null) story.setTitleKz(clean(req.getTitleKz()));
    if (req.getTitleRu() != null) story.setTitleRu(clean(req.getTitleRu()));
    if (req.getTitleEn() != null) story.setTitleEn(clean(req.getTitleEn()));
    if (req.getHeadingKz() != null) story.setHeadingKz(clean(req.getHeadingKz()));
    if (req.getHeadingRu() != null) story.setHeadingRu(clean(req.getHeadingRu()));
    if (req.getHeadingEn() != null) story.setHeadingEn(clean(req.getHeadingEn()));
    if (req.getBodyKz() != null) story.setBodyKz(clean(req.getBodyKz()));
    if (req.getBodyRu() != null) story.setBodyRu(clean(req.getBodyRu()));
    if (req.getBodyEn() != null) story.setBodyEn(clean(req.getBodyEn()));
    if (req.getCtaLabelKz() != null) story.setCtaLabelKz(clean(req.getCtaLabelKz()));
    if (req.getCtaLabelRu() != null) story.setCtaLabelRu(clean(req.getCtaLabelRu()));
    if (req.getCtaLabelEn() != null) story.setCtaLabelEn(clean(req.getCtaLabelEn()));
    if (req.getCtaUrl() != null) story.setCtaUrl(clean(req.getCtaUrl()));
    if (req.getEmoji() != null) story.setEmoji(clean(req.getEmoji()));
    if (req.getGradient() != null) story.setGradient(clean(req.getGradient()));
    if (req.getSortOrder() != null) story.setSortOrder(req.getSortOrder());
  }

  private String clean(String value) {
    return TextSanitizer.sanitize(value);
  }

  private void requireAnyTitle(Story story) {
    if (!isNonBlank(story.getTitleKz())
        && !isNonBlank(story.getTitleRu())
        && !isNonBlank(story.getTitleEn())) {
      throw new InvalidRequestException("At least one title is required");
    }
  }

  private boolean isNonBlank(String s) {
    return s != null && !s.isBlank();
  }

  private int clampSize(int size) {
    if (size <= 0) return 20;
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private StoryDto toDto(Story story) {
    return StoryDto.builder()
        .id(story.getId())
        .titleKz(story.getTitleKz())
        .titleRu(story.getTitleRu())
        .titleEn(story.getTitleEn())
        .headingKz(story.getHeadingKz())
        .headingRu(story.getHeadingRu())
        .headingEn(story.getHeadingEn())
        .bodyKz(story.getBodyKz())
        .bodyRu(story.getBodyRu())
        .bodyEn(story.getBodyEn())
        .ctaLabelKz(story.getCtaLabelKz())
        .ctaLabelRu(story.getCtaLabelRu())
        .ctaLabelEn(story.getCtaLabelEn())
        .ctaUrl(story.getCtaUrl())
        .emoji(story.getEmoji())
        .gradient(story.getGradient())
        .imageUrl(
            imageStorage.publicUrl(
                Stream.of(
                        story.getImageKey(),
                        story.getImageKeyRu(),
                        story.getImageKeyKz(),
                        story.getImageKeyEn())
                    .filter(Objects::nonNull)
                    .filter(key -> !key.isBlank())
                    .findFirst()
                    .orElse(null)))
        .imageUrlKz(imageStorage.publicUrl(story.getImageKeyKz()))
        .imageUrlRu(imageStorage.publicUrl(story.getImageKeyRu()))
        .imageUrlEn(imageStorage.publicUrl(story.getImageKeyEn()))
        .status(story.getStatus())
        .publishedAt(story.getPublishedAt())
        .sortOrder(story.getSortOrder())
        .createdAt(story.getCreatedAt())
        .updatedAt(story.getUpdatedAt())
        .build();
  }

  private ObjectNode snapshot(Story story) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("titleKz", story.getTitleKz());
    node.put("titleRu", story.getTitleRu());
    node.put("titleEn", story.getTitleEn());
    node.put("headingKz", story.getHeadingKz());
    node.put("headingRu", story.getHeadingRu());
    node.put("headingEn", story.getHeadingEn());
    node.put("bodyKz", story.getBodyKz());
    node.put("bodyRu", story.getBodyRu());
    node.put("bodyEn", story.getBodyEn());
    node.put("ctaUrl", story.getCtaUrl());
    node.put("imageKey", story.getImageKey());
    node.put("imageKeyKz", story.getImageKeyKz());
    node.put("imageKeyRu", story.getImageKeyRu());
    node.put("imageKeyEn", story.getImageKeyEn());
    node.put("status", story.getStatus() != null ? story.getStatus().name() : null);
    node.put("sortOrder", story.getSortOrder());
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
}
