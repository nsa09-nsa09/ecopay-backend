package kz.hrms.splitupauth.controller;

import kz.hrms.splitupauth.dto.LegalDocumentDto;
import kz.hrms.splitupauth.service.LegalDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public read-only access to the current legal document (Terms of Service / Privacy consent).
 * Mounted under {@code /api/v1/site} so it's covered by the existing {@code /api/v1/site/**}
 * permitAll rule in SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/site/legal")
@RequiredArgsConstructor
public class LegalDocumentController {

  private final LegalDocumentService service;

  @GetMapping("/{docType}")
  public ResponseEntity<LegalDocumentDto> get(@PathVariable String docType) {
    return ResponseEntity.ok(service.getPublic(service.parseDocType(docType)));
  }
}
