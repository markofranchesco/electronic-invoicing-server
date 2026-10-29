package pe.invoicing.app.repository.artifact.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.repository.artifact.DocumentArtifactEntityRepository;
import pe.invoicing.app.repository.artifact.IssueOperationEntityRepository;
import pe.invoicing.app.repository.artifact.entity.DocumentArtifactEntity;
import pe.invoicing.app.repository.artifact.entity.IssueOperationEntity;
import pe.invoicing.app.repository.identity.TaxpayerEntityRepository;
import pe.invoicing.app.service.document.models.DocumentArtifactData;
import pe.invoicing.app.service.document.models.IssueOperation;
import pe.invoicing.app.service.document.models.IssueOperationData;
import pe.invoicing.app.service.document.port.DocumentArtifactStore;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class DocumentArtifactStoreImpl implements DocumentArtifactStore {
  private final TaxpayerEntityRepository taxpayers;
  private final DocumentArtifactEntityRepository repository;
  private final IssueOperationEntityRepository operations;

  private static UUID key(UUID documentId, String type) {
    return UUID.nameUUIDFromBytes((documentId + ":" + type).getBytes(StandardCharsets.UTF_8));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void saveArtifact(DocumentArtifactData data) {
    UUID taxpayerId = taxpayers.companyId(data.accountId(), data.documentNumber());
    UUID id = key(data.documentId(), data.artifactType());
    DocumentArtifactEntity entity =
            repository
                    .findById(id)
                    .orElseGet(
                            () ->
                                    DocumentArtifactEntity.builder()
                                            .id(id)
                                            .taxpayerId(taxpayerId)
                                            .documentId(data.documentId())
                                            .build());
    if (!taxpayerId.equals(entity.getTaxpayerId())
            || !data.documentId().equals(entity.getDocumentId())) {
      throw new ResponseStatusException(
              HttpStatus.CONFLICT, "Identificador de archivo inconsistente.");
    }
    if ((data.artifactType().contains(":") || java.util.Set.of("XML", "SIGNED_XML").contains(data.artifactType())) && entity.getContent() != null
            && !java.util.Arrays.equals(entity.getContent(), data.content()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "No se puede alterar un archivo tributario histórico.");
    entity.setArtifactType(
            switch (data.artifactType().split(":", 2)[0]) {
              case "CDR" -> DocumentArtifactEntity.ArtifactType.CDR_ZIP;
              case "SUMMARY_XML" -> DocumentArtifactEntity.ArtifactType.SUMMARY_XML;
              case "CANCELLATION_XML" -> DocumentArtifactEntity.ArtifactType.CANCELLATION_XML;
              case "CANCELLATION_CDR" -> DocumentArtifactEntity.ArtifactType.CANCELLATION_CDR;
              case "XML", "SIGNED_XML" -> DocumentArtifactEntity.ArtifactType.SIGNED_XML;
              default -> throw new ResponseStatusException(
                      HttpStatus.UNPROCESSABLE_CONTENT, "Tipo de archivo tributario no soportado.");
            });
    entity.setContent(data.content().clone());
    repository.save(entity);
  }

  @Override
  public Optional<byte[]> findArtifact(
          UUID accountId, String documentNumber, UUID document, String type) {
    UUID taxpayerId = taxpayers.companyId(accountId, documentNumber);
    return repository
            .findById(key(document, type))
            .filter(
                    entity ->
                            taxpayerId.equals(entity.getTaxpayerId())
                                    && document.equals(entity.getDocumentId()))
            .map(DocumentArtifactEntity::getContent)
            .map(byte[]::clone);
  }

  @Override
  public Optional<IssueOperation> findIssueOperation(
          UUID accountId, String documentNumber, String hash) {
    return operations
            .findByTaxpayerIdAndKeyHash(taxpayers.companyId(accountId, documentNumber), hash)
            .map(entity -> new IssueOperation(entity.getBodyHash(), entity.getDocumentId()));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void saveIssueOperation(IssueOperationData data) {
    Optional<IssueOperation> existing =
            findIssueOperation(data.accountId(), data.documentNumber(), data.keyHash());
    if (existing.isPresent()) {
      if (!existing.get().bodyHash().equals(data.bodyHash())) {
        throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Clave de idempotencia utilizada para otro contenido.");
      }
      return;
    }
    operations.save(
            IssueOperationEntity.builder()
                    .id(UUID.randomUUID())
                    .taxpayerId(taxpayers.companyId(data.accountId(), data.documentNumber()))
                    .documentId(data.documentId())
                    .keyHash(data.keyHash())
                    .bodyHash(data.bodyHash())
                    .build());
  }
}
