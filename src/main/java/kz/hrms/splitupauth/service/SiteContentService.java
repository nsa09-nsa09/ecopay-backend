package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
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

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SiteContentService {

    private final SiteContentRepository repository;
    private final AdminActionLogRepository adminActionLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public SiteContentDto getAbout() {
        SiteContent content = repository.findById(SiteContent.SINGLETON_ID)
                .orElseThrow(() -> new ResourceNotFoundException("Site content not initialized"));
        return SiteContentDto.from(content);
    }

    @Transactional
    public SiteContentDto updateAbout(User admin, UpdateSiteContentRequest req, HttpServletRequest http) {
        SiteContent content = repository.findById(SiteContent.SINGLETON_ID)
                .orElseThrow(() -> new ResourceNotFoundException("Site content not initialized"));

        ObjectNode oldState = snapshot(content);

        // Strip any HTML/script-like markup before persisting. The public page
        // renders these fields as plain text via React, but defense-in-depth
        // matches the rest of the app's text fields (CLAUDE.md "Sanitize user
        // text fields on backend").
        content.setCompanyName(TextSanitizer.sanitize(req.getCompanyName()));
        content.setTitle(TextSanitizer.sanitize(req.getTitle()));
        content.setMission(TextSanitizer.sanitize(req.getMission()));
        content.setDescription(TextSanitizer.sanitize(req.getDescription()));
        content.setContactEmail(TextSanitizer.sanitize(req.getContactEmail()));
        content.setContactPhone(TextSanitizer.sanitize(req.getContactPhone()));
        content.setUpdatedBy(admin);

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
                        .build()
        );

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
        return node;
    }
}
