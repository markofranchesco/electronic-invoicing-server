package pe.invoicing.app.service.document.port;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import pe.invoicing.app.service.cancellation.models.CancellationState;
import pe.invoicing.app.service.document.models.DocumentData;
import pe.invoicing.app.service.document.models.DocumentSearchCriteria;

import java.util.Optional;
import java.util.UUID;

public interface DocumentStore {
  java.util.List<DocumentData> findCreditNotes(java.util.UUID accountId, String documentNumber, java.util.UUID sourceId);

  Page<pe.invoicing.app.service.document.models.ReconciliationTarget> reconciliationCandidates(Pageable pageable);

  Optional<UUID> claimReconciliation(UUID accountId, String documentNumber, UUID id);

  void finishReconciliation(UUID accountId, String documentNumber, UUID id, UUID token, String failure);

  void resumeReconciliation(UUID accountId, String documentNumber, UUID id);

  Page<DocumentData> findDocuments(
          UUID accountId, DocumentSearchCriteria criteria, Pageable pageable);

  DocumentData findDocument(UUID accountId, String documentNumber, UUID documentId);

  DocumentData saveDocument(UUID accountId, DocumentData document);

  DocumentData createIssuance(UUID accountId, DocumentData draft, String keyHash, String bodyHash);

  DocumentData publishDocument(UUID accountId, String documentNumber, UUID id, String key);

  java.util.Optional<pe.invoicing.app.service.document.models.PublishedDocument> findPublishedDocument(String key);

  DocumentData requestEmission(UUID accountId, String documentNumber, UUID id);

  DocumentData claimGrant(UUID accountId, String documentNumber, UUID id);

  Optional<DocumentData> claimEmission(UUID accountId, String documentNumber, UUID id);

  Optional<DocumentData> claimCancellation(
          UUID accountId, String documentNumber, UUID id, CancellationState cancellation);

  DocumentData saveWithArtifact(UUID accountId, DocumentData document, String type, byte[] content);

  Optional<DocumentData> claimRecovery(UUID accountId, String documentNumber, UUID id, boolean cancellation);

  Optional<DocumentData> claimSummaryReport(UUID accountId, String documentNumber, UUID id, String previousReference);
}
