package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import kz.hrms.splitupauth.dto.LegalDocumentDto;
import kz.hrms.splitupauth.dto.UpdateLegalDocumentRequest;
import kz.hrms.splitupauth.entity.AdminActionLog;
import kz.hrms.splitupauth.entity.AdminActionType;
import kz.hrms.splitupauth.entity.LegalDocument;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.LegalDocumentRepository;
import kz.hrms.splitupauth.util.TextSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LegalDocumentService {

    private final LegalDocumentRepository repository;
    private final AdminActionLogRepository adminActionLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public LegalDocumentDto getPublic(LegalDocument.DocType docType) {
        return LegalDocumentDto.from(loadOrThrow(docType));
    }

    @Transactional(readOnly = true)
    public LegalDocumentDto adminGet(LegalDocument.DocType docType) {
        return LegalDocumentDto.from(loadOrThrow(docType));
    }

    @Transactional(readOnly = true)
    public Integer currentVersion(LegalDocument.DocType docType) {
        return repository.findByDocType(docType)
                .map(LegalDocument::getVersion)
                .orElse(null);
    }

    @Transactional
    public LegalDocumentDto adminUpdate(User admin,
                                        LegalDocument.DocType docType,
                                        UpdateLegalDocumentRequest req,
                                        HttpServletRequest http) {
        LegalDocument doc = loadOrThrow(docType);

        ObjectNode oldState = snapshot(doc);

        // Sanitize only fields that were sent. TextSanitizer preserves
        // newlines (it only strips HTML), so multi-paragraph legal text keeps
        // its whitespace when re-rendered with whitespace-pre-line on the FE.
        if (req.getTitleKz() != null) doc.setTitleKz(TextSanitizer.sanitize(req.getTitleKz()));
        if (req.getTitleRu() != null) doc.setTitleRu(TextSanitizer.sanitize(req.getTitleRu()));
        if (req.getTitleEn() != null) doc.setTitleEn(TextSanitizer.sanitize(req.getTitleEn()));

        if (req.getBodyKz() != null) doc.setBodyKz(TextSanitizer.sanitize(req.getBodyKz()));
        if (req.getBodyRu() != null) doc.setBodyRu(TextSanitizer.sanitize(req.getBodyRu()));
        if (req.getBodyEn() != null) doc.setBodyEn(TextSanitizer.sanitize(req.getBodyEn()));

        doc.setVersion((doc.getVersion() == null ? 0 : doc.getVersion()) + 1);
        doc.setUpdatedBy(admin);

        doc = repository.save(doc);

        ObjectNode newState = snapshot(doc);
        adminActionLogRepository.save(
                AdminActionLog.builder()
                        .eventId(UUID.randomUUID())
                        .adminUser(admin)
                        .actionType(AdminActionType.LEGAL_DOCUMENT_UPDATED)
                        .entityType("LEGAL_DOCUMENT")
                        .entityId(doc.getId())
                        .reason(null)
                        .oldState(oldState)
                        .newState(newState)
                        .ipAddress(http != null ? http.getRemoteAddr() : null)
                        .userAgent(http != null ? http.getHeader("User-Agent") : null)
                        .build()
        );

        return LegalDocumentDto.from(doc);
    }

    /**
     * Parse a path-segment value ("terms" | "privacy", any case) into the enum.
     * Rejects unknown values with a 400 instead of 500-ing on IllegalArg.
     */
    public LegalDocument.DocType parseDocType(String raw) {
        if (raw == null) {
            throw new InvalidRequestException("docType is required");
        }
        try {
            return LegalDocument.DocType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new InvalidRequestException("Unknown legal document type: " + raw);
        }
    }

    private LegalDocument loadOrThrow(LegalDocument.DocType docType) {
        return repository.findByDocType(docType).orElseThrow(() ->
                new ResourceNotFoundException("Legal document not initialized: " + docType));
    }

    private ObjectNode snapshot(LegalDocument d) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("docType", d.getDocType() != null ? d.getDocType().name() : null);
        node.put("version", d.getVersion());
        node.put("titleKz", d.getTitleKz());
        node.put("titleRu", d.getTitleRu());
        node.put("titleEn", d.getTitleEn());
        node.put("bodyKz", d.getBodyKz());
        node.put("bodyRu", d.getBodyRu());
        node.put("bodyEn", d.getBodyEn());
        return node;
    }
}
