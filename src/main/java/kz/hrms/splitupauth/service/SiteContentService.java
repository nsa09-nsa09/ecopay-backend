package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import kz.hrms.splitupauth.dto.SiteContentDto;
import kz.hrms.splitupauth.dto.UpdateSiteContentRequest;
import kz.hrms.splitupauth.entity.AdminActionLog;
import kz.hrms.splitupauth.entity.AdminActionType;
import kz.hrms.splitupauth.entity.SiteContent;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.SiteContentRepository;
import kz.hrms.splitupauth.util.TextSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SiteContentService {

  private final SiteContentRepository repository;
  private final AdminActionLogRepository adminActionLogRepository;
  private final ObjectMapper objectMapper;

  @Transactional(readOnly = true)
  public SiteContentDto getAbout() {
    SiteContent content =
        repository
            .findById(SiteContent.SINGLETON_ID)
            .orElseThrow(() -> new ResourceNotFoundException("Site content not initialized"));
    return SiteContentDto.from(content);
  }

  @Transactional
  public SiteContentDto updateAbout(
      User admin, UpdateSiteContentRequest req, HttpServletRequest http) {
    SiteContent content =
        repository
            .findById(SiteContent.SINGLETON_ID)
            .orElseThrow(() -> new ResourceNotFoundException("Site content not initialized"));

    ObjectNode oldState = snapshot(content);

    // Strip any HTML/script-like markup before persisting. The public page
    // renders these fields as plain text via React, but defense-in-depth
    // matches the rest of the app's text fields (CLAUDE.md "Sanitize user
    // text fields on backend").
    String legacyTitle = TextSanitizer.sanitize(req.getTitle());
    String legacyMission = TextSanitizer.sanitize(req.getMission());
    String legacyDescription = TextSanitizer.sanitize(req.getDescription());

    content.setCompanyName(TextSanitizer.sanitize(req.getCompanyName()));
    content.setTitle(legacyTitle);
    content.setMission(legacyMission);
    content.setDescription(legacyDescription);
    content.setContactEmail(TextSanitizer.sanitize(req.getContactEmail()));
    content.setContactPhone(TextSanitizer.sanitize(req.getContactPhone()));
    if (req.getApexLink() != null) content.setApexLink(TextSanitizer.sanitize(req.getApexLink()));
    content.setUpdatedBy(admin);

    // Per-language fields: when present, sanitize + assign; when absent,
    // leave the existing value alone (so partial updates don't blank out
    // languages the admin didn't touch in this submission).
    if (req.getTitleKz() != null) content.setTitleKz(TextSanitizer.sanitize(req.getTitleKz()));
    if (req.getTitleRu() != null) content.setTitleRu(TextSanitizer.sanitize(req.getTitleRu()));
    if (req.getTitleEn() != null) content.setTitleEn(TextSanitizer.sanitize(req.getTitleEn()));

    if (req.getMissionKz() != null)
      content.setMissionKz(TextSanitizer.sanitize(req.getMissionKz()));
    if (req.getMissionRu() != null)
      content.setMissionRu(TextSanitizer.sanitize(req.getMissionRu()));
    if (req.getMissionEn() != null)
      content.setMissionEn(TextSanitizer.sanitize(req.getMissionEn()));

    if (req.getDescriptionKz() != null)
      content.setDescriptionKz(TextSanitizer.sanitize(req.getDescriptionKz()));
    if (req.getDescriptionRu() != null)
      content.setDescriptionRu(TextSanitizer.sanitize(req.getDescriptionRu()));
    if (req.getDescriptionEn() != null)
      content.setDescriptionEn(TextSanitizer.sanitize(req.getDescriptionEn()));

    // If the admin only edited the legacy fields (old UI), mirror them to
    // *_ru so the default-locale view doesn't go stale. We only do this
    // when the *_ru slot isn't explicitly being set in this request.
    if (req.getTitleRu() == null) content.setTitleRu(legacyTitle);
    if (req.getMissionRu() == null) content.setMissionRu(legacyMission);
    if (req.getDescriptionRu() == null) content.setDescriptionRu(legacyDescription);

    content = repository.save(content);

    ObjectNode newState = snapshot(content);
    adminActionLogRepository.save(
        AdminActionLog.builder()
            .eventId(UUID.randomUUID())
            .adminUser(admin)
            .actionType(AdminActionType.SITE_CONTENT_UPDATED)
            .entityType("SITE_CONTENT")
            .entityId(content.getId())
            .reason(null)
            .oldState(oldState)
            .newState(newState)
            .ipAddress(http != null ? http.getRemoteAddr() : null)
            .userAgent(http != null ? http.getHeader("User-Agent") : null)
            .build());

    return SiteContentDto.from(content);
  }

  private ObjectNode snapshot(SiteContent c) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("companyName", c.getCompanyName());
    node.put("title", c.getTitle());
    node.put("mission", c.getMission());
    node.put("description", c.getDescription());
    node.put("contactEmail", c.getContactEmail());
    node.put("contactPhone", c.getContactPhone());
    node.put("apexLink", c.getApexLink());
    node.put("titleKz", c.getTitleKz());
    node.put("titleRu", c.getTitleRu());
    node.put("titleEn", c.getTitleEn());
    node.put("missionKz", c.getMissionKz());
    node.put("missionRu", c.getMissionRu());
    node.put("missionEn", c.getMissionEn());
    node.put("descriptionKz", c.getDescriptionKz());
    node.put("descriptionRu", c.getDescriptionRu());
    node.put("descriptionEn", c.getDescriptionEn());
    return node;
  }
}
