package kz.hrms.splitupauth.repository;

import java.util.Optional;
import kz.hrms.splitupauth.entity.LegalDocument;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LegalDocumentRepository extends JpaRepository<LegalDocument, Long> {

  Optional<LegalDocument> findByDocType(LegalDocument.DocType docType);
}
