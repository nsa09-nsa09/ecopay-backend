package kz.hrms.splitupauth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.LegalDocumentDto;
import kz.hrms.splitupauth.dto.UpdateLegalDocumentRequest;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.LegalDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/legal")
@RequiredArgsConstructor
public class AdminLegalDocumentController {

  private final LegalDocumentService service;

  @GetMapping("/{docType}")
  public ResponseEntity<LegalDocumentDto> get(@PathVariable String docType) {
    return ResponseEntity.ok(service.adminGet(service.parseDocType(docType)));
  }

  @PutMapping("/{docType}")
  public ResponseEntity<LegalDocumentDto> update(
      @AuthenticationPrincipal User admin,
      @PathVariable String docType,
      @Valid @RequestBody UpdateLegalDocumentRequest request,
      HttpServletRequest httpRequest) {
    return ResponseEntity.ok(
        service.adminUpdate(admin, service.parseDocType(docType), request, httpRequest));
  }
}
