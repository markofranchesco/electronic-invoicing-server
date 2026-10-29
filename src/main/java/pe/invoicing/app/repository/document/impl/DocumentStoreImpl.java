package pe.invoicing.app.repository.document.impl;

import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.repository.document.DocumentEntityRepository;
import pe.invoicing.app.repository.document.entity.DocumentEntity;
import pe.invoicing.app.repository.document.mappers.DocumentPersistenceMapper;
import pe.invoicing.app.repository.identity.TaxpayerEntityRepository;
import pe.invoicing.app.service.cancellation.models.CancellationState;
import pe.invoicing.app.service.company.port.SeriesStore;
import pe.invoicing.app.service.document.models.DocumentArtifactData;
import pe.invoicing.app.service.document.models.DocumentData;
import pe.invoicing.app.service.document.models.DocumentSearchCriteria;
import pe.invoicing.app.service.document.models.IssueOperation;
import pe.invoicing.app.service.document.models.IssueOperationData;
import pe.invoicing.app.service.document.port.DocumentArtifactStore;
import pe.invoicing.app.service.document.port.DocumentStore;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class DocumentStoreImpl implements DocumentStore {
  private final TaxpayerEntityRepository taxpayers;
  private final Clock clock;
  private final DocumentEntityRepository repository;
  private final SeriesStore sequences;
  private final DocumentArtifactStore artifacts;
  private final DocumentPersistenceMapper mapper;

  @Override
  @Transactional(readOnly = true)
  public List<DocumentData> findCreditNotes(UUID accountId, String documentNumber, UUID sourceId) {
    own(accountId, documentNumber, sourceId);
    return repository.findByTaxpayerIdAndSourceDocumentId(taxpayers.companyId(accountId, documentNumber), sourceId)
            .stream().map(mapper::toModel).toList();
  }

  private DocumentData own(UUID accountId, String documentNumber, UUID id) {
    UUID taxpayerId = taxpayers.companyId(accountId, documentNumber);
    return repository
            .findById(id)
            .filter(entity -> taxpayerId.equals(entity.getTaxpayerId()))
            .map(mapper::toModel)
            .orElseThrow(
                    () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Documento no encontrado."));
  }

  @Override
  public Page<DocumentData> findDocuments(
          UUID accountId, DocumentSearchCriteria criteria, Pageable pageable) {
    UUID taxpayerId = taxpayers.companyId(accountId, criteria.documentNumber());
    Specification<DocumentEntity> filter =
            (root, query, builder) -> {
              List<Predicate> predicates = new ArrayList<>();
              predicates.add(builder.equal(root.get("taxpayerId"), taxpayerId));
              Optional.ofNullable(criteria.documentType())
                      .ifPresent(
                              type ->
                                      predicates.add(builder.equal(root.get("documentType"), mapper.toType(type))));
              Optional.ofNullable(criteria.status())
                      .ifPresent(
                              status ->
                                      predicates.add(
                                              builder.equal(
                                                      root.get("status"),
                                                      DocumentEntity.DocumentStatus.valueOf(
                                                              "PENDING".equals(status) ? "CREATED" : status))));
              Optional.ofNullable(criteria.series())
                      .ifPresent(series -> predicates.add(builder.equal(root.get("series"), series)));
              Optional.ofNullable(criteria.number())
                      .ifPresent(number -> predicates.add(builder.equal(root.get("number"), number)));
              Optional.ofNullable(criteria.externalReference())
                      .ifPresent(
                              reference ->
                                      predicates.add(builder.equal(root.get("externalReference"), reference)));
              Optional.ofNullable(criteria.from())
                      .ifPresent(
                              date ->
                                      predicates.add(
                                              builder.greaterThanOrEqualTo(
                                                      root.get("issueDate"),
                                                      date.atStartOfDay().atOffset(ZoneOffset.ofHours(-5)))));
              Optional.ofNullable(criteria.to())
                      .ifPresent(
                              date ->
                                      predicates.add(
                                              builder.lessThan(
                                                      root.get("issueDate"),
                                                      date.plusDays(1).atStartOfDay().atOffset(ZoneOffset.ofHours(-5)))));
              return builder.and(predicates.toArray(Predicate[]::new));
            };
    return repository.findAll(filter, pageable).map(mapper::toModel);
  }

  @Override
  public DocumentData findDocument(UUID accountId, String documentNumber, UUID id) {
    return own(accountId, documentNumber, id);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public DocumentData saveDocument(UUID accountId, DocumentData document) {
    UUID taxpayerId = taxpayers.companyId(accountId, document.getDocumentNumber());
    taxpayers.lockCompany(accountId, document.getDocumentNumber())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no encontrada."));
    var existing = repository.findById(document.getId());
    if (existing.isPresent() && !taxpayerId.equals(existing.orElseThrow().getTaxpayerId()))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Documento no encontrado.");
    long currentVersion = existing.map(mapper::toModel).map(DocumentData::getVersion).orElse(0L);
    if (currentVersion != Optional.ofNullable(document.getVersion()).orElse(0L))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "El documento cambió durante la operación; consulta y concilia otra vez.");
    // Una nota modifica el saldo de la venta: invalida lecturas previas de caja bajo el mismo bloqueo.
    // El origen sólo puede ser factura/boleta; no se crean cadenas recursivas de notas.
    if (document.getSourceDocumentId() != null && java.util.Set.of("07", "08").contains(document.getDocumentType())) {
      var source = own(accountId, document.getDocumentNumber(), document.getSourceDocumentId());
      if (!java.util.Set.of("01", "03").contains(source.getDocumentType()))
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Origen de nota inválido.");
      saveDocument(accountId, source);
    }
    document.setVersion(currentVersion + 1);
    DocumentEntity entity =
            mapper.toEntity(
                    document.toBuilder().updatedAt(OffsetDateTime.now(clock)).build(), taxpayerId);
    entity.setTaxpayer(taxpayers.getReferenceById(taxpayerId));
    DocumentData saved = mapper.toModel(repository.saveAndFlush(entity));
    document.setVersion(saved.getVersion());
    return saved;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public DocumentData createIssuance(
          UUID accountId, DocumentData draft, String keyHash, String bodyHash) {
    taxpayers
            .lockCompany(accountId, draft.getDocumentNumber())
            .orElseThrow(
                    () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no encontrada."));
    Optional<IssueOperation> existing =
            artifacts.findIssueOperation(accountId, draft.getDocumentNumber(), keyHash);
    if (existing.isPresent()) {
      if (!existing.get().bodyHash().equals(bodyHash)) {
        throw new ResponseStatusException(
                HttpStatus.CONFLICT, "La clave de idempotencia corresponde a otra solicitud.");
      }
      return own(accountId, draft.getDocumentNumber(), existing.get().documentId());
    }
    Optional.ofNullable(draft.getSourceDocumentId())
            .ifPresent(
                    source -> {
                      DocumentData original = own(accountId, draft.getDocumentNumber(), source);
                      List<DocumentData> previous = repository.findByTaxpayerIdAndSourceDocumentId(
                                      taxpayers.companyId(accountId, draft.getDocumentNumber()), source)
                              .stream().map(mapper::toModel).toList();
                      if ("08".equals(draft.getDocumentType()))
                        pe.invoicing.app.service.debitnote.DebitNoteRules.validate(original, draft, previous);
                      else pe.invoicing.app.service.creditnote.models.CreditNoteRules.validateBalance(original, draft, previous);
                    });
    sequences.ensureSequence(
            accountId, draft.getDocumentNumber(), draft.getDocumentType(), draft.getSeries());
    draft.setNumber(
            sequences.allocateNextNumber(
                    accountId, draft.getDocumentNumber(), draft.getDocumentType(), draft.getSeries()));
    saveDocument(accountId, draft);
    artifacts.saveIssueOperation(
            new IssueOperationData(
                    accountId, draft.getDocumentNumber(), keyHash, bodyHash, draft.getId()));
    return draft;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public DocumentData requestEmission(UUID accountId, String documentNumber, UUID id) {
    taxpayers.lockCompany(accountId, documentNumber)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no encontrada."));
    DocumentData document = own(accountId, documentNumber, id);
    if (!"HELD".equals(document.getStatus())) return document;
    if (!document.getIssueDate().equals(LocalDate.now(clock)))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "La venta pendiente debe emitirse en su fecha de emisión.");
    document.setStatus("DRAFT");
    return saveDocument(accountId, document);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Optional<DocumentData> claimEmission(UUID accountId, String documentNumber, UUID id) {
    taxpayers
            .lockCompany(accountId, documentNumber)
            .orElseThrow(
                    () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no encontrada."));
    DocumentData document = own(accountId, documentNumber, id);
    if (!Set.of("DRAFT", "PREPARATION_FAILED").contains(document.getStatus())) {
      return Optional.empty();
    }
    document.setStatus("PREPARING");
    document.getCommunication().setLastAttemptAt(OffsetDateTime.now(clock));
    if (document.getCommunication().getFirstAttemptAt() == null)
      document.getCommunication().setFirstAttemptAt(OffsetDateTime.now(clock));
    if ("DAILY_SUMMARY".equals(document.getCommunication().getMethod())) {
      if (Optional.ofNullable(document.getCommunication().getBatchReference()).isEmpty()) {
        document
                .getCommunication()
                .setBatchReference(
                        "RC-"
                                + LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE)
                                + "-"
                                + sequences.allocateCommunicationNumber(accountId, documentNumber, "RC", LocalDate.now(clock)));
      }
    }
    saveDocument(accountId, document);
    return Optional.of(document);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Optional<DocumentData> claimCancellation(
          UUID accountId, String documentNumber, UUID id, CancellationState cancellation) {
    taxpayers
            .lockCompany(accountId, documentNumber)
            .orElseThrow(
                    () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no encontrada."));
    DocumentData document = own(accountId, documentNumber, id);
    if (document.getGrantedAt() != null)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "El comprobante ya fue otorgado.");
    boolean unreportedReceipt = document.getSeries().startsWith("B")
            && "REJECTED".equals(document.getStatus()) && Boolean.TRUE.equals(document.getXmlAvailable());
    if (!unreportedReceipt && !"ACCEPTED".equals(document.getApiStatus()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "El comprobante ya no permite solicitar una baja.");
    if (repository.existsByTaxpayerIdAndSourceDocumentIdAndStatusNotIn(
            taxpayers.companyId(accountId, documentNumber),
            id,
            List.of(DocumentEntity.DocumentStatus.REJECTED, DocumentEntity.DocumentStatus.ANNULLED)))
      throw new ResponseStatusException(
              HttpStatus.CONFLICT, "El comprobante tiene una nota de crédito vigente.");

    CancellationState previous = document.getCancellation();
    if (previous != null) {
      if ("PREPARATION_FAILED".equals(previous.getStatus())) {
        cancellation.setId(previous.getId());
        cancellation.setReference(previous.getReference());
        cancellation.setRequestedAt(previous.getRequestedAt());
      } else if ("REJECTED".equals(previous.getStatus()) && previous.getReference().equals(cancellation.getPreviousReference())) {
        var history = new ArrayList<>(Optional.ofNullable(document.getCancellationHistory()).orElseGet(List::of));
        history.add(previous);
        document.setCancellationHistory(history);
        for (String type : List.of("CANCELLATION_XML", "CANCELLATION_CDR")) {
          if (artifacts.findArtifact(accountId, documentNumber, id, type + ":" + previous.getReference()).isEmpty())
            artifacts.findArtifact(accountId, documentNumber, id, type).ifPresent(bytes -> artifacts.saveArtifact(
                    new DocumentArtifactData(accountId, documentNumber, id, type + ":" + previous.getReference(), bytes)));
        }
      } else return Optional.empty();
    }
    if (Optional.ofNullable(cancellation.getReference()).isEmpty()) {
      cancellation.setReference(
              (document.getSeries().startsWith("B") ? "RC" : "RA")
                      + "-"
                      + LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE)
                      + "-"
                      + sequences.allocateCommunicationNumber(accountId, documentNumber,
                      document.getSeries().startsWith("B") ? "RC" : "RA", LocalDate.now(clock)));
    }
    cancellation.setLastAttemptAt(OffsetDateTime.now(clock));
    document.setCancellation(cancellation);
    saveDocument(accountId, document);
    return Optional.of(document);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public DocumentData saveWithArtifact(
          UUID accountId, DocumentData document, String type, byte[] data) {
    artifacts.saveArtifact(
            new DocumentArtifactData(
                    accountId, document.getDocumentNumber(), document.getId(), type, data));
    String reference = type.startsWith("CANCELLATION") ? document.getCancellation().getReference()
            : ("SUMMARY_XML".equals(type) || "CDR".equals(type) && document.getSeries().startsWith("B"))
            ? document.getCommunication().getBatchReference() : null;
    if (reference != null) artifacts.saveArtifact(new DocumentArtifactData(accountId, document.getDocumentNumber(),
            document.getId(), type + ":" + reference, data));
    return saveDocument(accountId, document);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Optional<DocumentData> claimRecovery(UUID accountId, String documentNumber, UUID id, boolean cancellation) {
    taxpayers.lockCompany(accountId, documentNumber)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no encontrada."));
    DocumentData document = own(accountId, documentNumber, id);
    String status = cancellation
            ? Optional.ofNullable(document.getCancellation()).map(CancellationState::getStatus).orElse("")
            : document.getStatus();
    if (!(cancellation && "PENDING".equals(status))
            && !Set.of("UNKNOWN", "PREPARING", "PREPARATION_FAILED", "SEND_FAILED", "SUMMARY_PREPARING", "SUMMARY_PREPARATION_FAILED").contains(status))
      return Optional.empty();
    OffsetDateTime previous = cancellation ? document.getCancellation().getLastAttemptAt()
            : document.getCommunication().getLastAttemptAt();
    if (previous != null && previous.isAfter(OffsetDateTime.now(clock).minusMinutes(2)))
      return Optional.empty();
    if (cancellation) document.getCancellation().setLastAttemptAt(OffsetDateTime.now(clock));
    else {
      if (document.getCommunication().getFirstAttemptAt() == null)
        document.getCommunication().setFirstAttemptAt(previous == null ? document.getCreatedAt() : previous);
      document.getCommunication().setLastAttemptAt(OffsetDateTime.now(clock));
    }
    saveDocument(accountId, document);
    return Optional.of(document);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Optional<DocumentData> claimSummaryReport(UUID accountId, String documentNumber, UUID id, String previousReference) {
    taxpayers.lockCompany(accountId, documentNumber)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no encontrada."));
    DocumentData document = own(accountId, documentNumber, id);
    var communication = document.getCommunication();
    if (previousReference.equals(communication.getPreviousReference())) return Optional.empty();
    if (!previousReference.equals(communication.getBatchReference())
            || !Set.of("REPORTED", "REJECTED").contains(communication.getStatus())
            || !Set.of("ACCEPTED", "ACCEPTED_WITH_OBSERVATIONS", "REJECTED").contains(document.getStatus())
            || document.getCancellation() != null)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "El resumen actual no permite otro reporte.");
    for (String type : List.of("SUMMARY_XML", "CDR")) {
      if (artifacts.findArtifact(accountId, documentNumber, id, type + ":" + previousReference).isPresent())
        continue;
      artifacts.findArtifact(accountId, documentNumber, id, type).ifPresent(content ->
              artifacts.saveArtifact(new DocumentArtifactData(accountId, documentNumber, id,
                      type + ":" + previousReference, content)));
    }
    var history = new ArrayList<>(Optional.ofNullable(document.getCommunicationHistory()).orElseGet(List::of));
    history.add(communication.toBuilder().build());
    document.setCommunicationHistory(history);
    communication.setPreviousReference(previousReference);
    communication.setPreviousDocumentStatus(document.getStatus());
    communication.setCondition(document.getStatus().startsWith("ACCEPTED") ? "2" : "1");
    communication.setBatchReference("RC-" + LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE)
            + "-" + sequences.allocateCommunicationNumber(accountId, documentNumber, "RC", LocalDate.now(clock)));
    communication.setTicket(null);
    communication.setLastAttemptAt(OffsetDateTime.now(clock));
    communication.setStatus("PREPARING");
    document.setStatus("SUMMARY_PREPARING");
    saveDocument(accountId, document);
    return Optional.of(document);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public DocumentData claimGrant(UUID accountId, String documentNumber, UUID id) {
    return publishDocument(accountId, documentNumber, id, UUID.randomUUID().toString().replace("-", ""));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public DocumentData publishDocument(UUID accountId, String documentNumber, UUID id, String key) {
    taxpayers.lockCompany(accountId, documentNumber)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no encontrada."));
    DocumentData document = own(accountId, documentNumber, id);
    if (document.getPublicationKey() != null) return document;
    if (!Set.of("ACCEPTED", "ACCEPTED_WITH_OBSERVATIONS", "SUBMITTED").contains(document.getStatus())
            || document.getCancellation() != null && !"REJECTED".equals(document.getCancellation().getStatus()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "No se puede publicar este comprobante.");
    if (artifacts.findArtifact(accountId, documentNumber, id, "XML").isEmpty()
            || document.getStatus().startsWith("ACCEPTED") && artifacts.findArtifact(accountId, documentNumber, id, "CDR").isEmpty())
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Faltan los archivos originales.");
    document.setPublicationKey(key);
    if (document.getGrantedAt() == null) document.setGrantedAt(OffsetDateTime.now(clock));
    return saveDocument(accountId, document);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<pe.invoicing.app.service.document.models.PublishedDocument> findPublishedDocument(String key) {
    return repository.findByPublicationKey(key).map(entity -> new pe.invoicing.app.service.document.models.PublishedDocument(
            entity.getTaxpayer().getAccountId(), mapper.toModel(entity)));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<pe.invoicing.app.service.document.models.ReconciliationTarget> reconciliationCandidates(Pageable pageable) {
    var page = repository.findAll(pageable);
    var targets = page.getContent().stream()
            .filter(entity -> pe.invoicing.app.service.document.models.ReconciliationState.pending(mapper.toModel(entity)))
            .map(entity -> new pe.invoicing.app.service.document.models.ReconciliationTarget(
                    entity.getTaxpayer().getAccountId(), entity.getTaxpayer().getDocumentNumber(), entity.getId())).toList();
    // Mantener el total y la paginación del escaneo, incluso si esta página sólo contiene documentos finales.
    return new org.springframework.data.domain.PageImpl<>(targets, pageable, page.getTotalElements());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Optional<UUID> claimReconciliation(UUID accountId, String documentNumber, UUID id) {
    taxpayers.lockCompany(accountId, documentNumber).orElseThrow();
    DocumentData document = own(accountId, documentNumber, id);
    if (!pe.invoicing.app.service.document.models.ReconciliationState.pending(document)) return Optional.empty();
    var state = document.getReconciliation();
    if (state == null) {
      state = new pe.invoicing.app.service.document.models.ReconciliationState();
      document.setReconciliation(state);
    }
    var now = OffsetDateTime.now(clock);
    if (state.isManualReview() || state.getLeaseUntil() != null && state.getLeaseUntil().isAfter(now)
            || state.getNextAttemptAt() != null && state.getNextAttemptAt().isAfter(now)) return Optional.empty();
    if (state.getAttempts() >= 8) {
      state.setManualReview(true);
      saveDocument(accountId, document);
      return Optional.empty();
    }
    UUID token = UUID.randomUUID();
    state.setLeaseToken(token);
    state.setLeaseUntil(now.plusMinutes(5));
    state.setAttempts(state.getAttempts() + 1);
    saveDocument(accountId, document);
    return Optional.of(token);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void finishReconciliation(UUID accountId, String documentNumber, UUID id, UUID token, String failure) {
    taxpayers.lockCompany(accountId, documentNumber).orElseThrow();
    DocumentData document = own(accountId, documentNumber, id);
    var state = document.getReconciliation();
    if (state == null || !token.equals(state.getLeaseToken())) return;
    state.setLeaseToken(null);
    state.setLeaseUntil(null);
    state.setLastError(failure == null ? null : failure.substring(0, Math.min(1000, failure.length())));
    boolean pending = pe.invoicing.app.service.document.models.ReconciliationState.pending(document);
    state.setManualReview(pending && (state.getAttempts() >= 8 || failure != null && failure.startsWith("MANUAL:")));
    state.setNextAttemptAt(!pending || state.isManualReview() ? null
            : OffsetDateTime.now(clock).plusSeconds(Math.min(3600, 30L << Math.min(state.getAttempts(), 7))));
    saveDocument(accountId, document);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void resumeReconciliation(UUID accountId, String documentNumber, UUID id) {
    taxpayers.lockCompany(accountId, documentNumber).orElseThrow();
    DocumentData document = own(accountId, documentNumber, id);
    if (document.getReconciliation() != null && document.getReconciliation().getLeaseUntil() != null
            && document.getReconciliation().getLeaseUntil().isAfter(OffsetDateTime.now(clock)))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Conciliación en curso.");
    if ("UNKNOWN".equals(document.getStatus()) && "MANUAL_REVIEW".equals(document.getCommunication().getStatus()))
      document.getCommunication().setStatus("UNKNOWN");
    if (document.getCancellation() != null && "MANUAL_REVIEW".equals(document.getCancellation().getStatus()))
      document.getCancellation().setStatus("UNKNOWN");
    document.setReconciliation(new pe.invoicing.app.service.document.models.ReconciliationState());
    saveDocument(accountId, document);
  }

}
