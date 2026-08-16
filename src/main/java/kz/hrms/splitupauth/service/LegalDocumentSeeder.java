package kz.hrms.splitupauth.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import kz.hrms.splitupauth.entity.LegalDocument;
import kz.hrms.splitupauth.repository.LegalDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Boot-time idempotent seeder for {@code legal_documents}.
 *
 * <p>For each {@link LegalDocument.DocType}, if the row does not already exist, it is created with
 * {@code version=1} and the body loaded from the classpath-bundled {@code legal/*.md} resource files
 * (one per language). If the row already exists it is left untouched, so an admin's edits survive
 * redeploys.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LegalDocumentSeeder {

  private final LegalDocumentRepository repository;

  @EventListener(ApplicationReadyEvent.class)
  @Transactional
  public void seed() {
    seedIfMissing(
        LegalDocument.DocType.TERMS,
        "Пайдалану шарттары",
        "Пользовательское соглашение",
        "Terms of Service",
        "legal/terms_kz.md",
        "legal/terms_ru.md",
        "legal/terms_en.md");
    seedIfMissing(
        LegalDocument.DocType.PRIVACY,
        "Дербес деректерді өңдеуге келісім",
        "Согласие на обработку персональных данных",
        "Consent to Personal Data Processing",
        "legal/privacy_kz.md",
        "legal/privacy_ru.md",
        "legal/privacy_en.md");
  }

  private void seedIfMissing(
      LegalDocument.DocType docType,
      String titleKz,
      String titleRu,
      String titleEn,
      String bodyKzResource,
      String bodyRuResource,
      String bodyEnResource) {
    if (repository.findByDocType(docType).isPresent()) {
      return;
    }

    LegalDocument doc =
        LegalDocument.builder()
            .docType(docType)
            .version(1)
            .titleKz(titleKz)
            .titleRu(titleRu)
            .titleEn(titleEn)
            .bodyKz(loadResource(bodyKzResource))
            .bodyRu(loadResource(bodyRuResource))
            .bodyEn(loadResource(bodyEnResource))
            .build();
    repository.save(doc);
    log.info("Seeded legal document {} from classpath resources", docType);
  }

  private String loadResource(String path) {
    ClassPathResource resource = new ClassPathResource(path);
    try (InputStream in = resource.getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      log.warn("Legal document seed resource missing: {}; leaving column empty", path);
      return null;
    }
  }
}
