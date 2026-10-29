package pe.invoicing.app.service.document;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import pe.invoicing.app.service.document.models.DocumentData;
import pe.invoicing.app.service.document.models.DocumentDraft;
import pe.invoicing.app.service.document.models.DocumentSearchCriteria;
import pe.invoicing.app.service.document.models.SubmissionResult;

import java.util.UUID;

public interface DocumentService {
  Page<DocumentData> listDocuments(DocumentSearchCriteria criteria, Pageable pageable);

  DocumentData getDocument(String documentNumber, UUID id);

  SubmissionResult savePendingDocument(String documentNumber, String idempotency, DocumentDraft body);

  SubmissionResult acceptNewDocument(String documentNumber, String idempotency, DocumentDraft body);

  SubmissionResult issueNewDocument(String documentNumber, String idempotency, DocumentDraft body);

  byte[] downloadPdf(String documentNumber, UUID id, String format);

  DocumentData recoverDocument(String documentNumber, UUID id, String ticket);

  byte[] downloadArtifact(String documentNumber, UUID id, String type);

  DocumentData reportSummary(String documentNumber, UUID id, String previousReference);

  pe.invoicing.app.service.document.models.DocumentFile downloadFile(String documentNumber, UUID id, String type);

  pe.invoicing.app.service.document.models.DocumentFile downloadCommunicationFile(String documentNumber, UUID id, String reference, String type);

  java.util.List<pe.invoicing.app.service.document.models.CommunicationRecord> communicationHistory(String documentNumber, UUID id);

  void resumeReconciliation(String documentNumber, UUID id);

  DocumentData markGranted(String documentNumber, UUID id);
}
