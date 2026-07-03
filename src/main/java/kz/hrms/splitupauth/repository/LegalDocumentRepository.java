package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.LegalDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LegalDocumentRepository extends JpaRepository<LegalDocument, Long> {

    Optional<LegalDocument> findByDocType(LegalDocument.DocType docType);
}
