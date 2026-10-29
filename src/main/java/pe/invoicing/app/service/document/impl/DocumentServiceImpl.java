package pe.invoicing.app.service.document.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.config.sunat.properties.SunatProperties;
import pe.invoicing.app.service.authentication.AutenticacionService;
import pe.invoicing.app.service.authentication.port.SecurityCryptography;
import pe.invoicing.app.service.company.CompanyService;
import pe.invoicing.app.service.company.models.Company;
import pe.invoicing.app.service.document.DocumentService;
import pe.invoicing.app.service.document.models.CommunicationState;
import pe.invoicing.app.service.document.models.DocumentArtifactData;
import pe.invoicing.app.service.document.models.DocumentData;
import pe.invoicing.app.service.document.models.DocumentDraft;
import pe.invoicing.app.service.document.models.DocumentSearchCriteria;
import pe.invoicing.app.service.document.models.DocumentTotals;
import pe.invoicing.app.service.document.models.IssueOperation;
import pe.invoicing.app.service.document.models.LineAmounts;
import pe.invoicing.app.service.document.models.SubmissionResult;
import pe.invoicing.app.service.document.models.SunatFaultException;
import pe.invoicing.app.service.document.models.SunatSendResult;
import pe.invoicing.app.service.document.models.SunatStatus;
import pe.invoicing.app.service.document.models.TaxResult;
import pe.invoicing.app.service.document.port.DocumentArtifactStore;
import pe.invoicing.app.service.document.port.DocumentStore;
import pe.invoicing.app.service.document.port.SunatGateway;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT;

@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {
  private final SecurityCryptography cryptography;
  private final Clock clock;
  private final AutenticacionService auth;
  private final CompanyService companies;
  private final DocumentStore store;
  private final DocumentArtifactStore artifacts;
  private final DocumentPdfRenderer pdfRenderer;
  private final SunatGateway gateway;
  private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
  private final SunatProperties sunatProperties;

  @Override
  public Page<DocumentData> listDocuments(DocumentSearchCriteria criteria, Pageable pageable) {
    return store.findDocuments(auth.accountId(), criteria, pageable);
  }

  private void validateIssueDate(DocumentDraft body) {
    if (body.issueDate().isAfter(LocalDate.now(clock))
            || body.issueDate()
            .isBefore(LocalDate.now(clock).minusDays(body.series().startsWith("B") ? 7 : 3)))
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Fecha fuera del plazo de emisión.");
  }

  private DocumentTotals totals(DocumentDraft body) {
    if (!Set.of("01", "03", "07", "08").contains(body.documentType()) || body.items().isEmpty())
      throw new ResponseStatusException(
              UNPROCESSABLE_CONTENT, "Tipo de comprobante no admitido o detalle vacío.");
    if (!Set.of("PEN", "USD", "EUR").contains(body.currency()))
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Moneda no admitida.");
    if ("01".equals(body.documentType()) && Optional.ofNullable(body.payment()).isEmpty())
      throw new ResponseStatusException(
              UNPROCESSABLE_CONTENT, "La factura requiere forma de pago.");
    Optional.ofNullable(body.payment())
            .ifPresent(
                    payment -> {
                      if (!Set.of("CASH", "CREDIT").contains(payment.getType())
                              || ("CASH".equals(payment.getType())
                              && (Optional.ofNullable(payment.getPendingAmount()).isPresent()
                              || !Optional.ofNullable(payment.getInstallments())
                              .orElseGet(List::of)
                              .isEmpty())))
                        throw new ResponseStatusException(
                                UNPROCESSABLE_CONTENT, "Forma de pago incompatible con saldo o cuotas.");
                    });
    validateIssueDate(body);
    if (!body.series()
            .startsWith(
                    "03".equals(body.documentType())
                            ? "B"
                            : Set.of("07", "08").contains(body.documentType())
                            ? body.note().getOriginalDocument().getSeries().substring(0, 1)
                            : "F"))
      throw new ResponseStatusException(
              UNPROCESSABLE_CONTENT, "La serie no corresponde al tipo de comprobante.");
    if (!Set.of("0101", "1001").contains(body.operationType()))
      throw new ResponseStatusException(
              UNPROCESSABLE_CONTENT, "Operación fiscal no implementada; no se envió a SUNAT.");
    if ("01".equals(body.documentType()) && !"6".equals(body.customer().getDocumentType()))
      throw new ResponseStatusException(
              UNPROCESSABLE_CONTENT, "Factura nacional requiere cliente con RUC.");
    List<LineAmounts> lines;
    try {
      lines = body.items().stream().map(LineAmounts::calculateLineAmounts).toList();
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, exception.getMessage());
    }
    DocumentTotals totals =
            DocumentTotals.builder()
                    .taxableAmount(
                            lines.stream()
                                    .filter(line -> "10".equals(line.affectation()))
                                    .map(LineAmounts::base)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                                    .toPlainString())
                    .exemptAmount(
                            lines.stream()
                                    .filter(line -> "20".equals(line.affectation()))
                                    .map(LineAmounts::base)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                                    .toPlainString())
                    .unaffectedAmount(
                            lines.stream()
                                    .filter(line -> "30".equals(line.affectation()))
                                    .map(LineAmounts::base)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                                    .toPlainString())
                    .igvAmount(
                            lines.stream()
                                    .map(LineAmounts::tax)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                                    .toPlainString())
                    .discountAmount(
                            lines.stream()
                                    .map(LineAmounts::discountTotal)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                                    .toPlainString())
                    .totalAmount(
                            lines.stream()
                                    .map(LineAmounts::total)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                                    .toPlainString())
                    .build();
    if (new BigDecimal(totals.getTotalAmount()).compareTo(new BigDecimal("1000000000000")) >= 0)
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "El importe total excede los 12 enteros admitidos.");
    if (body.series().startsWith("B")
            && !"PEN".equals(body.currency())
            && Optional.ofNullable(body.exchangeRate()).isEmpty())
      throw new ResponseStatusException(
              UNPROCESSABLE_CONTENT, "Boleta en moneda extranjera requiere tipo de cambio.");
    if ("PEN".equals(body.currency()) && body.exchangeRate() != null
            && new BigDecimal(body.exchangeRate()).compareTo(BigDecimal.ONE) != 0)
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "El tipo de cambio en soles debe ser uno.");
    if (Optional.ofNullable(body.exchangeRate())
            .map(BigDecimal::new)
            .filter(rate -> rate.signum() <= 0)
            .isPresent())
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Tipo de cambio debe ser positivo.");
    if ("0".equals(body.customer().getDocumentType())
            && new BigDecimal(totals.getTotalAmount())
            .multiply(new BigDecimal(Optional.ofNullable(body.exchangeRate()).orElse("1")))
            .compareTo(new BigDecimal("700"))
            > 0)
      throw new ResponseStatusException(
              UNPROCESSABLE_CONTENT, "Identifica al cliente para este importe.");
    Optional.ofNullable(body.payment())
            .filter(payment -> "CREDIT".equals(payment.getType()))
            .ifPresent(
                    payment -> {
                      if (payment.getInstallments().isEmpty()
                              || payment.getInstallments().stream()
                              .anyMatch(part -> new BigDecimal(part.getAmount()).signum() <= 0)
                              || new BigDecimal(payment.getPendingAmount()).signum() <= 0
                              || !("07".equals(body.documentType()) && "13".equals(body.note().getReasonCode()))
                              && payment.getInstallments().stream()
                              .anyMatch(part -> !part.getDueDate().isAfter(body.issueDate()))
                              || payment.getInstallments().stream()
                              .map(part -> new BigDecimal(part.getAmount()))
                              .reduce(BigDecimal.ZERO, BigDecimal::add)
                              .compareTo(new BigDecimal(payment.getPendingAmount()))
                              != 0
                              || !("07".equals(body.documentType()) && "13".equals(body.note().getReasonCode()))
                              && new BigDecimal(payment.getPendingAmount())
                              .compareTo(new BigDecimal(totals.getTotalAmount()))
                              > 0)
                        throw new ResponseStatusException(
                                UNPROCESSABLE_CONTENT,
                                "Revisa saldo, suma de cuotas y vencimientos posteriores a emisión.");
                    });
    if (body.detraction() != null) body.detraction().validate(body, totals);
    var bagTotal = lines.stream().map(LineAmounts::icbper).reduce(BigDecimal.ZERO, BigDecimal::add);
    return bagTotal.signum() == 0 ? totals : totals.toBuilder().icbperAmount(bagTotal.toPlainString()).build();
  }

  @Override
  public SubmissionResult issueNewDocument(
          String documentNumber, String idempotency, DocumentDraft body) {
    return issueDocument(documentNumber, idempotency, body, false, false);
  }

  @Override
  public SubmissionResult acceptNewDocument(String documentNumber, String idempotency, DocumentDraft body) {
    return issueDocument(documentNumber, idempotency, body, true, false);
  }

  @Override
  public SubmissionResult savePendingDocument(String documentNumber, String idempotency, DocumentDraft body) {
    return issueDocument(documentNumber, idempotency, body, true, true);
  }

  private SubmissionResult issueDocument(String documentNumber, String idempotency, DocumentDraft body, boolean deferred, boolean held) {
    if (!StringUtils.hasText(idempotency) || idempotency.length() < 8 || idempotency.length() > 200)
      throw new ResponseStatusException(
              UNPROCESSABLE_CONTENT, "Indica una clave de idempotencia de 8 a 200 caracteres.");
    DocumentDraftValidator.validate(body);
    String keyHash = cryptography.sha256(idempotency);
    String bodyHash = cryptography.sha256(json.valueToTree(body).toString());
    Optional<IssueOperation> existing =
            artifacts.findIssueOperation(auth.accountId(), documentNumber, keyHash);
    if (existing.isPresent()) {
      if (!existing.orElseThrow().bodyHash().equals(bodyHash))
        throw new ResponseStatusException(
                CONFLICT, "La clave de idempotencia corresponde a otra solicitud.");
      if (!Set.of("DRAFT", "PREPARATION_FAILED")
              .contains(
                      store
                              .findDocument(
                                      auth.accountId(), documentNumber, existing.orElseThrow().documentId())
                              .getStatus()))
        return submission(store.findDocument(auth.accountId(), documentNumber, existing.orElseThrow().documentId()));
      validateIssueDate(body);
    }
    Company company = companies.getCompany(documentNumber);
    if (!held && !company.getReady())
      throw new ResponseStatusException(
              UNPROCESSABLE_CONTENT, "Completa certificado y credenciales antes de emitir.");
    DocumentDraft resolved = pe.invoicing.app.service.document.models.TaxRates.resolve(body, company);
    if (!held) gateway.checkReady(documentNumber);
    DocumentData current =
            existing
                    .map(
                            operation ->
                                    store.findDocument(auth.accountId(), documentNumber, operation.documentId()))
                    .orElseGet(
                            () ->
                                    createValidatedIssuance(
                                            DocumentData.builder()
                                                    .id(UUID.randomUUID())
                                                    .documentNumber(documentNumber)
                                                    .environment(sunatProperties.environment())
                                                    .sourceDocumentId(body.sourceDocumentId())
                                                    .documentType(body.documentType())
                                                    .series(body.series())
                                                    .issueDate(body.issueDate())
                                                    .currency(body.currency())
                                                    .customer(body.customer())
                                                    .items(resolved.items())
                                                    .payment(body.payment())
                                                    .note(body.note())
                                                    .operationType(body.operationType())
                                                    .detraction(body.detraction())
                                                    .externalReference(body.externalReference())
                                                    .observations(body.observations())
                                                    .exchangeRate(body.exchangeRate())
                                                    .issuerLegalName(company.getLegalName())
                                                    .issuerLogoBase64(company.getLogoBase64())
                                                    .issuerFiscalAddress(body.establishment() == null ? company.getAddress().getStreet() : body.establishment().address().getStreet())
                                                    .issuerAddress((body.establishment() == null ? company.getAddress() : body.establishment().address()).toBuilder().build())
                                                    .establishment(body.establishment())
                                                    .totals(totals(resolved))
                                                    .status(held ? "HELD" : "DRAFT")
                                                    .xmlAvailable(false)
                                                    .cdrAvailable(false)
                                                    .createdAt(OffsetDateTime.now(clock))
                                                    .updatedAt(OffsetDateTime.now(clock))
                                                    .communication(
                                                            CommunicationState.builder()
                                                                    .method(
                                                                            body.series().startsWith("B") ? "DAILY_SUMMARY" : "DIRECT")
                                                                    .status("PENDING")
                                                                    .build())
                                                    .build(),
                                            keyHash,
                                            bodyHash));
    // DRAFT y la clave quedan confirmados en la misma transacción antes del HTTP 202.
    // El conciliador recupera la emisión incluso si el proceso termina a continuación.
    if (deferred) return submission(current);
    UUID id = current.getId();
    if (Set.of("DRAFT", "PREPARATION_FAILED").contains(current.getStatus())) {
      if (!Objects.equals(current.getEnvironment(), sunatProperties.environment()))
        throw new ResponseStatusException(
                CONFLICT, "El documento pertenece a otro entorno de emisión.");
      gateway.validateSupported(current);
    }
    Optional<DocumentData> claimed = store.claimEmission(auth.accountId(), documentNumber, id);
    if (claimed.isEmpty()) return submission(getDocument(documentNumber, id));
    DocumentData document = claimed.orElseThrow();
    byte[] signed;
    byte[] summary = new byte[0];
    try {
      Optional<byte[]> previousXml = artifacts.findArtifact(auth.accountId(), documentNumber, id, "XML");
      if (previousXml.isEmpty() && document.getSignedXmlSha256() != null)
        throw new ResponseStatusException(CONFLICT, "Falta un XML que ya fue firmado; restaura el original.");
      signed = previousXml.isPresent() ? previousXml.orElseThrow() : gateway.signedXml(document, company);
      verifySignedXml(document, signed);
      if ("DAILY_SUMMARY".equals(document.getCommunication().getMethod()))
        summary = artifacts.findArtifact(auth.accountId(), documentNumber, id,
                        "SUMMARY_XML:" + document.getCommunication().getBatchReference())
                .or(() -> artifacts.findArtifact(auth.accountId(), documentNumber, id, "SUMMARY_XML"))
                .orElseGet(() -> gateway.signedSummary(
                        document,
                        company,
                        document.getCommunication().getBatchReference(),
                        document.getCommunication().getLastAttemptAt().toLocalDate(),
                        "1"));
    } catch (RuntimeException exception) {
      document.setStatus("PREPARATION_FAILED");
      store.saveDocument(auth.accountId(), document);
      throw exception;
    }
    document.setXmlAvailable(true);
    document.setSignedXmlSha256(cryptography.sha256(signed));
    document.setStatus("UNKNOWN");
    document.setUpdatedAt(OffsetDateTime.now(clock));
    if (summary.length > 0)
      artifacts.saveArtifact(
              new DocumentArtifactData(auth.accountId(), documentNumber, id, "SUMMARY_XML", summary));
    store.saveWithArtifact(auth.accountId(), document, "XML", signed);
    SunatSendResult receipt;
    try {
      if (summary.length > 0) {
        document
                .getCommunication()
                .setTicket(
                        gateway.sendSummary(
                                documentNumber, document.getCommunication().getBatchReference(), summary));
        document.getCommunication().setStatus("SUBMITTED");
        document.setStatus("SUBMITTED");
        document.setIssuedAt(OffsetDateTime.now(clock));
        store.saveDocument(auth.accountId(), document);
        return submission(document);
      }
      receipt = gateway.sendBill(document, signed);
    } catch (ResponseStatusException exception) {
      recordSendFailure(document, exception);
      throw exception;
    }
    return submission(applyReceipt(document, receipt, false));
  }

  private DocumentData createValidatedIssuance(DocumentData draft, String keyHash, String bodyHash) {
    // Validar el contenido antes de reservar el correlativo real.
    gateway.validateSupported(draft.toBuilder().number(1L).build());
    return store.createIssuance(auth.accountId(), draft, keyHash, bodyHash);
  }

  private DocumentData applyReceipt(DocumentData document, SunatSendResult receipt, boolean recovered) {
    OffsetDateTime acceptedAt = Optional.ofNullable(document.getTaxResponse())
            .map(TaxResult::getAcceptedAt).orElse(null);
    OffsetDateTime deadlineBase = acceptedAt;
    String dateSource = acceptedAt == null ? null : "RECORDED_RECEIPT";
    if (receipt.accepted() && acceptedAt == null) {
      if (!recovered) {
        acceptedAt = OffsetDateTime.now(clock);
        deadlineBase = acceptedAt;
        dateSource = "RECEIVED_LOCALLY";
      } else {
        deadlineBase = Optional.ofNullable(document.getCommunication().getFirstAttemptAt())
                .orElse(Optional.ofNullable(document.getCommunication().getLastAttemptAt()).orElse(document.getCreatedAt()));
        dateSource = "FIRST_ATTEMPT_ESTIMATE";
      }
    }
    var communication = document.getCommunication();
    communication.setResponseCode(receipt.code());
    communication.setResponseDescription(receipt.description());
    if ("DAILY_SUMMARY".equals(communication.getMethod()))
      artifacts.saveArtifact(new DocumentArtifactData(auth.accountId(), document.getDocumentNumber(),
              document.getId(), "CDR:" + communication.getBatchReference(), receipt.cdrZip()));
    if ("2".equals(communication.getCondition()) && !receipt.accepted()) {
      document.setStatus(communication.getPreviousDocumentStatus());
      communication.setStatus("REJECTED");
      return store.saveDocument(auth.accountId(), document);
    }
    document.setStatus(receipt.accepted() ? "ACCEPTED" : "REJECTED");
    if (receipt.accepted() && !receipt.observations().isEmpty())
      document.setStatus("ACCEPTED_WITH_OBSERVATIONS");
    document.setCdrAvailable(true);
    if (document.getIssuedAt() == null) document.setIssuedAt(OffsetDateTime.now(clock));
    if (document.getReportedAt() == null) document.setReportedAt(OffsetDateTime.now(clock));
    document.setUpdatedAt(OffsetDateTime.now(clock));
    document.setTaxResponse(
            TaxResult.builder()
                    .code(receipt.code())
                    .description(receipt.description())
                    .observations(receipt.observations())
                    .source(sunatProperties.environment())
                    .checkedAt(OffsetDateTime.now(clock))
                    .acceptedAt(receipt.accepted() ? acceptedAt : null)
                    .acceptanceDeadlineBase(receipt.accepted() ? deadlineBase : null)
                    .acceptanceDateSource(receipt.accepted() ? dateSource : null)
                    .reference(document.getCommunication().getBatchReference())
                    .build());
    document.getCommunication().setStatus(receipt.accepted() ? "REPORTED" : "REJECTED");
    document.getCommunication().setConfirmedAt(OffsetDateTime.now(clock));
    document = store.saveWithArtifact(auth.accountId(), document, "CDR", receipt.cdrZip());
    return document;
  }

  private SubmissionResult submission(DocumentData document) {
    return SubmissionResult.builder().documentId(document.getId()).build();
  }

  @Override
  public DocumentData getDocument(String documentNumber, UUID id) {
    DocumentData document = store.findDocument(auth.accountId(), documentNumber, id);
    if (!Set.of("UNKNOWN", "SUBMITTED").contains(document.getStatus())) return document;
    if (!Objects.equals(document.getEnvironment(), sunatProperties.environment()))
      throw new ResponseStatusException(CONFLICT, "El documento pertenece a otro entorno de emisión.");
    if ("DAILY_SUMMARY".equals(document.getCommunication().getMethod())) {
      String ticket = document.getCommunication().getTicket();
      if (!StringUtils.hasText(ticket)) return document;
      SunatStatus result = gateway.getSummaryStatus(documentNumber, ticket,
              document.getCommunication().getBatchReference());
      if (result.hasConsistentSummaryReceipt())
        return applyReceipt(document, result.receipt().orElseThrow(), true);
      document.getCommunication().setResponseCode(result.code());
      document.getCommunication().setResponseDescription(result.summaryProcessing()
              ? "SUNAT sigue procesando el resumen."
              : "No hay una constancia coherente; el resultado tributario no está confirmado.");
      document.getCommunication().setStatus(result.summaryProcessing() ? "SUBMITTED"
              : result.summaryRequiresReview() ? "MANUAL_REVIEW" : "RESPONSE_INCOMPLETE");
      document.setStatus(result.summaryProcessing() ? "SUBMITTED" : "UNKNOWN");
      return store.saveDocument(auth.accountId(), document);
    }
    if (!sunatProperties.cdrConsultationAvailable()) return document;
    return gateway
            .consultBill(document)
            .receipt()
            .map(receipt -> applyReceipt(document, receipt, true))
            .orElse(document);
  }

  @Override
  public byte[] downloadPdf(String documentNumber, UUID id, String format) {
    return pdfRenderer.render(
            documentNumber,
            store.findDocument(auth.accountId(), documentNumber, id),
            format,
            artifacts.findArtifact(auth.accountId(), documentNumber, id, "XML"));
  }

  @Override
  public DocumentData recoverDocument(String documentNumber, UUID id, String ticket) {
    DocumentData document = store.findDocument(auth.accountId(), documentNumber, id);
    if (!Objects.equals(document.getEnvironment(), sunatProperties.environment()))
      throw new ResponseStatusException(CONFLICT, "El documento pertenece a otro entorno.");
    if (!Set.of("DRAFT", "UNKNOWN", "SUBMITTED", "PREPARING", "PREPARATION_FAILED", "SEND_FAILED", "SUMMARY_PREPARING", "SUMMARY_PREPARATION_FAILED").contains(document.getStatus()))
      return document;
    boolean summary = "DAILY_SUMMARY".equals(document.getCommunication().getMethod());
    if (summary && StringUtils.hasText(ticket)) {
      SunatStatus result = gateway.getSummaryStatus(documentNumber, ticket,
              document.getCommunication().getBatchReference());
      if (!result.hasConsistentSummaryReceipt())
        throw new ResponseStatusException(CONFLICT,
                "El ticket aún no tiene una CDR coherente que permita verificar su referencia.");
      SunatSendResult receipt = result.receipt().orElseThrow();
      document.getCommunication().setTicket(ticket);
      return applyReceipt(document, receipt, true);
    }
    if (summary && StringUtils.hasText(document.getCommunication().getTicket()))
      return getDocument(documentNumber, id);
    if (!summary && !Set.of("DRAFT", "PREPARING", "PREPARATION_FAILED").contains(document.getStatus())) {
      var receipt = gateway.consultBill(document).receipt();
      if (receipt.isPresent()) return applyReceipt(document, receipt.orElseThrow(), true);
    }
    // La reserva del correlativo puede sobrevivir a una caída anterior a claimEmission.
    // Reclamar la emisión existente también asigna su referencia RC, sin crear otra venta.
    boolean reservedOnly = "DRAFT".equals(document.getStatus());
    if (reservedOnly && LocalDate.now(clock).isAfter(document.getIssueDate().plusDays(summary ? 7 : 3)))
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Plazo de preparación vencido.");
    Optional<DocumentData> claimed = reservedOnly
            ? store.claimEmission(auth.accountId(), documentNumber, id)
            : store.claimRecovery(auth.accountId(), documentNumber, id, false);
    if (claimed.isEmpty()) return store.findDocument(auth.accountId(), documentNumber, id);
    document = claimed.orElseThrow();
    if (reservedOnly) document.setStatus("PREPARATION_FAILED");
    if ("PREPARATION_FAILED".equals(document.getStatus())) {
      if (LocalDate.now(clock).isAfter(document.getIssueDate().plusDays(summary ? 7 : 3)))
        throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Plazo de preparación vencido.");
      Optional<byte[]> existingXml = artifacts.findArtifact(auth.accountId(), documentNumber, id, "XML");
      if (existingXml.isEmpty() && document.getSignedXmlSha256() != null)
        throw new ResponseStatusException(CONFLICT, "Falta un XML que ya fue firmado; restaura el original.");
      existingXml.ifPresent(bytes -> verifySignedXml(claimed.orElseThrow(), bytes));
      byte[] prepared = existingXml.orElseGet(() -> gateway.signedXml(claimed.orElseThrow(), companies.getCompany(documentNumber)));
      if (summary) {
        String reference = document.getCommunication().getBatchReference();
        byte[] batch = artifacts.findArtifact(auth.accountId(), documentNumber, id, "SUMMARY_XML:" + reference)
                .orElseGet(() -> gateway.signedSummary(claimed.orElseThrow(), companies.getCompany(documentNumber), reference,
                        LocalDate.parse(reference.split("-")[1], java.time.format.DateTimeFormatter.BASIC_ISO_DATE), "1"));
        store.saveWithArtifact(auth.accountId(), document, "SUMMARY_XML", batch);
      }
      document.setXmlAvailable(true);
      document.setSignedXmlSha256(cryptography.sha256(prepared));
      document.setStatus("UNKNOWN");
      store.saveWithArtifact(auth.accountId(), document, "XML", prepared);
    }
    if (summary && Set.of("SUMMARY_PREPARING", "SUMMARY_PREPARATION_FAILED").contains(document.getStatus())) {
      var communication = document.getCommunication();
      byte[] prepared = gateway.signedSummary(document, companies.getCompany(documentNumber),
              communication.getBatchReference(), java.time.LocalDate.parse(
                      communication.getBatchReference().split("-")[1], java.time.format.DateTimeFormatter.BASIC_ISO_DATE),
              communication.getCondition());
      store.saveWithArtifact(auth.accountId(), document, "SUMMARY_XML", prepared);
      artifacts.saveArtifact(new DocumentArtifactData(auth.accountId(), documentNumber, id,
              "SUMMARY_XML:" + communication.getBatchReference(), prepared));
    }
    Optional<byte[]> signed = artifacts.findArtifact(auth.accountId(), documentNumber, id, "XML");
    if (signed.isEmpty()) {
      if (!"PREPARING".equals(document.getStatus()))
        throw new ResponseStatusException(CONFLICT, "No se puede reenviar sin el XML original.");
      document.setStatus("PREPARATION_FAILED");
      return store.saveDocument(auth.accountId(), document);
    }
    verifySignedXml(document, signed.orElseThrow());
    if (LocalDate.now(clock).isAfter(document.getIssueDate().plusDays(summary ? 7 : 3)))
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT,
              "Plazo de reenvío vencido; solo procede recuperar la constancia original.");
    byte[] payload = summary ? artifacts.findArtifact(auth.accountId(), documentNumber, id,
                    "SUMMARY_XML:" + document.getCommunication().getBatchReference())
            .or(() -> artifacts.findArtifact(auth.accountId(), documentNumber, id, "SUMMARY_XML"))
            .orElseThrow(() -> new ResponseStatusException(CONFLICT, "Falta el resumen original.")) : signed.orElseThrow();
    document.setStatus("UNKNOWN");
    store.saveDocument(auth.accountId(), document);
    try {
      if (summary) {
        document.getCommunication().setTicket(gateway.sendSummary(documentNumber,
                document.getCommunication().getBatchReference(), payload));
        document.getCommunication().setStatus("SUBMITTED");
        document.setStatus("SUBMITTED");
        return store.saveDocument(auth.accountId(), document);
      }
      return applyReceipt(document, gateway.sendBill(document, payload), true);
    } catch (ResponseStatusException exception) {
      recordSendFailure(document, exception);
      throw exception;
    }
  }

  private void verifySignedXml(DocumentData document, byte[] signed) {
    if (document.getSignedXmlSha256() != null
            && !document.getSignedXmlSha256().equals(cryptography.sha256(signed)))
      throw new ResponseStatusException(CONFLICT,
              "El XML firmado no coincide con su huella original; restaura el archivo antes de reenviar.");
  }

  private void recordSendFailure(DocumentData document, ResponseStatusException exception) {
    String code = "UNCONFIRMED";
    if (exception instanceof SunatFaultException fault) {
      code = fault.code();
      document.setStatus(fault.documentStatus());
      document.getCommunication().setStatus(fault.documentStatus());
    }
    if ("2".equals(document.getCommunication().getCondition())) {
      document.getCommunication().setResponseCode(code);
      document.getCommunication().setResponseDescription(exception.getReason());
      if ("REJECTED".equals(document.getStatus()))
        document.setStatus(document.getCommunication().getPreviousDocumentStatus());
      store.saveDocument(auth.accountId(), document);
      return;
    }
    document.setTaxResponse(TaxResult.builder().code(code)
            .description(exception.getReason()).source(sunatProperties.environment())
            .checkedAt(OffsetDateTime.now(clock))
            .acceptedAt(Optional.ofNullable(document.getTaxResponse()).map(TaxResult::getAcceptedAt).orElse(null))
            .build());
    store.saveDocument(auth.accountId(), document);
  }

  @Override
  public byte[] downloadArtifact(String documentNumber, UUID id, String type) {
    store.findDocument(auth.accountId(), documentNumber, id);
    return artifacts.findArtifact(auth.accountId(), documentNumber, id, type)
            .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
                    "El artefacto solicitado aún no está disponible."));
  }

  @Override
  public DocumentData reportSummary(String documentNumber, UUID id, String previousReference) {
    DocumentData document = store.findDocument(auth.accountId(), documentNumber, id);
    if (!Objects.equals(document.getEnvironment(), sunatProperties.environment()))
      throw new ResponseStatusException(CONFLICT, "El documento pertenece a otro entorno.");
    if (!"DAILY_SUMMARY".equals(document.getCommunication().getMethod())
            || !StringUtils.hasText(previousReference))
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Solo se reportan boletas y sus notas.");
    if (previousReference.equals(document.getCommunication().getPreviousReference())) return document;
    if (LocalDate.now(clock).isAfter(document.getIssueDate().plusDays(7)))
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Plazo del resumen diario vencido.");
    gateway.checkReady(documentNumber);
    Optional<DocumentData> claimed = store.claimSummaryReport(auth.accountId(), documentNumber, id, previousReference);
    if (claimed.isEmpty()) return store.findDocument(auth.accountId(), documentNumber, id);
    document = claimed.orElseThrow();
    var communication = document.getCommunication();
    byte[] signed;
    try {
      signed = gateway.signedSummary(document, companies.getCompany(documentNumber),
              communication.getBatchReference(), LocalDate.now(clock), communication.getCondition());
    } catch (RuntimeException exception) {
      document.setStatus("SUMMARY_PREPARATION_FAILED");
      communication.setStatus("PREPARATION_FAILED");
      store.saveDocument(auth.accountId(), document);
      throw exception;
    }
    document.setStatus("UNKNOWN");
    communication.setStatus("UNKNOWN");
    artifacts.saveArtifact(new DocumentArtifactData(auth.accountId(), documentNumber, id,
            "SUMMARY_XML:" + communication.getBatchReference(), signed));
    store.saveWithArtifact(auth.accountId(), document, "SUMMARY_XML", signed);
    try {
      communication.setTicket(gateway.sendSummary(documentNumber, communication.getBatchReference(), signed));
      communication.setStatus("SUBMITTED");
      document.setStatus("SUBMITTED");
      return store.saveDocument(auth.accountId(), document);
    } catch (ResponseStatusException exception) {
      recordSendFailure(document, exception);
      throw exception;
    }
  }

  @Override
  public pe.invoicing.app.service.document.models.DocumentFile downloadFile(String documentNumber, UUID id, String type) {
    DocumentData document = store.findDocument(auth.accountId(), documentNumber, id);
    if (!Set.of("XML", "CDR", "SUMMARY_XML", "SUMMARY_CDR", "CANCELLATION_XML", "CANCELLATION_CDR").contains(type))
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Tipo de archivo no admitido.");
    boolean cdr = type.endsWith("CDR");
    String reference = document.getDocumentType() + "-" + document.getSeries() + "-" + document.getNumber();
    String artifact = type;
    if (type.startsWith("CANCELLATION_")) {
      reference = Optional.ofNullable(document.getCancellation()).map(c -> c.getReference())
              .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
                      "El comprobante no tiene comunicación de baja."));
    } else if (type.startsWith("SUMMARY_") || (cdr && document.getSeries().startsWith("B"))) {
      if (!"DAILY_SUMMARY".equals(document.getCommunication().getMethod()))
        throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "El comprobante no tiene RC.");
      reference = document.getCommunication().getBatchReference();
      if (cdr) artifact = "CDR:" + reference;
    }
    byte[] content = downloadArtifact(documentNumber, id, artifact);
    return new pe.invoicing.app.service.document.models.DocumentFile(
            (cdr ? "R-" : "") + documentNumber + "-" + reference + (cdr ? ".zip" : ".xml"),
            cdr ? "application/zip" : "application/xml", content);
  }

  @Override
  public DocumentData markGranted(String documentNumber, UUID id) {
    DocumentData document = store.findDocument(auth.accountId(), documentNumber, id);
    if (!Set.of("ACCEPTED", "ACCEPTED_WITH_OBSERVATIONS", "SUBMITTED").contains(document.getStatus())
            || !Boolean.TRUE.equals(document.getXmlAvailable())
            || document.getCancellation() != null && !"REJECTED".equals(document.getCancellation().getStatus()))
      throw new ResponseStatusException(CONFLICT, "El documento no está disponible para otorgamiento.");
    return store.claimGrant(auth.accountId(), documentNumber, id);
  }

  @Override
  public pe.invoicing.app.service.document.models.DocumentFile downloadCommunicationFile(
          String documentNumber, UUID id, String reference, String type) {
    if (reference == null || !reference.matches("(?:RA|RC)-[0-9]{8}-[0-9]{1,5}")
            || !Set.of("SUMMARY_XML", "CDR", "CANCELLATION_XML", "CANCELLATION_CDR").contains(type))
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Referencia o archivo inválido.");
    DocumentData document = store.findDocument(auth.accountId(), documentNumber, id);
    boolean cancellation = type.startsWith("CANCELLATION");
    String current = cancellation ? Optional.ofNullable(document.getCancellation()).map(c -> c.getReference()).orElse("")
            : document.getCommunication().getBatchReference();
    byte[] content = artifacts.findArtifact(auth.accountId(), documentNumber, id, type + ":" + reference)
            .or(() -> reference.equals(current) ? artifacts.findArtifact(auth.accountId(), documentNumber, id, type) : Optional.empty())
            .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Archivo no disponible."));
    boolean cdr = type.endsWith("CDR");
    return new pe.invoicing.app.service.document.models.DocumentFile((cdr ? "R-" : "") + documentNumber + "-" + reference
            + (cdr ? ".zip" : ".xml"), cdr ? "application/zip" : "application/xml", content);
  }

  @Override
  public void resumeReconciliation(String documentNumber, UUID id) {
    store.resumeReconciliation(auth.accountId(), documentNumber, id);
  }

  @Override
  public List<pe.invoicing.app.service.document.models.CommunicationRecord> communicationHistory(String documentNumber, UUID id) {
    DocumentData document = store.findDocument(auth.accountId(), documentNumber, id);
    var records = new java.util.ArrayList<pe.invoicing.app.service.document.models.CommunicationRecord>();
    var summaries = new java.util.ArrayList<>(Optional.ofNullable(document.getCommunicationHistory()).orElseGet(List::of));
    summaries.add(document.getCommunication());
    for (var summary : summaries)
      if (summary.getBatchReference() != null)
        records.add(new pe.invoicing.app.service.document.models.CommunicationRecord(summary.getBatchReference(), "SUMMARY",
                summary.getStatus(), summary.getResponseCode(), summary.getResponseDescription(), summary.getTicket(),
                summary.getLastAttemptAt(), summary.getConfirmedAt()));
    var cancellations = new java.util.ArrayList<>(Optional.ofNullable(document.getCancellationHistory()).orElseGet(List::of));
    if (document.getCancellation() != null) cancellations.add(document.getCancellation());
    for (var cancellation : cancellations)
      records.add(new pe.invoicing.app.service.document.models.CommunicationRecord(cancellation.getReference(), "CANCELLATION",
              cancellation.getStatus(), cancellation.getSunatCode(), cancellation.getSunatDescription(), cancellation.getTicket(),
              cancellation.getLastAttemptAt(), cancellation.getCompletedAt()));
    return List.copyOf(records);
  }

}
