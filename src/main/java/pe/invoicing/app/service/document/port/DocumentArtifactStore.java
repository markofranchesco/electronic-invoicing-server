package pe.invoicing.app.service.document.port;

import pe.invoicing.app.service.document.models.DocumentArtifactData;
import pe.invoicing.app.service.document.models.IssueOperation;
import pe.invoicing.app.service.document.models.IssueOperationData;

import java.util.Optional;
import java.util.UUID;

public interface DocumentArtifactStore {
  void saveArtifact(DocumentArtifactData artifact);

  Optional<byte[]> findArtifact(
          UUID accountId, String documentNumber, UUID documentId, String type);

  Optional<IssueOperation> findIssueOperation(
          UUID accountId, String documentNumber, String keyHash);

  void saveIssueOperation(IssueOperationData operation);
}
