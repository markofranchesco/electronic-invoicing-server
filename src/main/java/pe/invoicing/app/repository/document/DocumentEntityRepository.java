package pe.invoicing.app.repository.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import pe.invoicing.app.repository.document.entity.DocumentEntity;

import java.util.List;
import java.util.UUID;

public interface DocumentEntityRepository
        extends JpaRepository<DocumentEntity, UUID>, JpaSpecificationExecutor<DocumentEntity> {
  boolean existsByTaxpayerIdAndSourceDocumentIdAndStatusNotIn(
          UUID taxpayerId, UUID sourceDocumentId, List<DocumentEntity.DocumentStatus> statuses);

  List<DocumentEntity> findByTaxpayerIdAndSourceDocumentId(UUID taxpayerId, UUID sourceDocumentId);

  java.util.Optional<DocumentEntity> findByPublicationKey(String publicationKey);

  boolean existsByTaxpayerId(UUID taxpayerId);
}
