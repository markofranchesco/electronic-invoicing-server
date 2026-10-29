package pe.invoicing.app.service.cancellation.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.config.sunat.properties.SunatProperties;
import pe.invoicing.app.service.authentication.AutenticacionService;
import pe.invoicing.app.service.cancellation.CancellationService;
import pe.invoicing.app.service.cancellation.models.CancellationDetails;
import pe.invoicing.app.service.cancellation.models.CancellationState;
import pe.invoicing.app.service.company.CompanyService;
import pe.invoicing.app.service.document.DocumentService;
import pe.invoicing.app.service.document.models.DocumentData;
import pe.invoicing.app.service.document.models.SunatFaultException;
import pe.invoicing.app.service.document.models.SunatSendResult;
import pe.invoicing.app.service.document.models.SunatStatus;
import pe.invoicing.app.service.document.port.DocumentStore;
import pe.invoicing.app.service.document.port.SunatGateway;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT;

@Service
@RequiredArgsConstructor
public class CancellationServiceImpl implements CancellationService {
  private final DocumentService documents;
  private final DocumentStore store;
  private final CompanyService companies;
  private final AutenticacionService auth;
  private final SunatGateway gateway;
  private final Clock clock;
  private final SunatProperties sunatProperties;

  @Override
  public CancellationState requestCancellation(
          String documentNumber, UUID id, CancellationDetails body) {
    return requestCancellation(documentNumber, id, body, false);
  }

  @Override
  public CancellationState acceptCancellation(String documentNumber, UUID id, CancellationDetails body) {
    return requestCancellation(documentNumber, id, body, true);
  }

  private CancellationState requestCancellation(String documentNumber, UUID id, CancellationDetails body, boolean deferred) {
    if (body == null || body.reason() == null || body.reason().isBlank()
            || body.reason().length() > 250 || body.reason().matches("(?s).*[\\r\\n\\t].*"))
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Indica un sustento de baja válido.");
    if (!Boolean.TRUE.equals(body.notGranted()))
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT,
              "La baja requiere confirmar que el documento no fue otorgado; usa nota de crédito.");
    DocumentData original = documents.getDocument(documentNumber, id);
    checkEnvironment(original);
    if (original.getGrantedAt() != null)
      throw new ResponseStatusException(CONFLICT, "El comprobante ya fue otorgado; usa nota de crédito.");
    String method = original.getSeries().startsWith("B") ? "RECEIPT_VOID" : "VOID_COMMUNICATION";
    String expectedProcedure =
            original.getSeries().startsWith("B") ? "DAILY_SUMMARY_VOID" : "COMMUNICATION_BAJA";
    if (!expectedProcedure.equals(body.procedure())) {
      throw new ResponseStatusException(
              UNPROCESSABLE_CONTENT,
              "El procedimiento de baja no corresponde al comprobante original.");
    }
    Optional<CancellationState> previous = Optional.ofNullable(original.getCancellation());
    if (previous.isPresent()) {
      CancellationState existing = previous.orElseThrow();
      boolean retry = "REJECTED".equals(existing.getStatus()) && existing.getReference().equals(body.previousReference());
      if (!retry) {
        if (!existing.getMethod().equals(method) || !existing.getReason().equals(body.reason()))
          throw new ResponseStatusException(CONFLICT, "Ya existe una baja con otros datos; indica la referencia rechazada para corregirla.");
        if (body.previousReference() != null && !body.previousReference().equals(existing.getPreviousReference()))
          throw new ResponseStatusException(CONFLICT, "La referencia de baja ya no es la actual.");
        if (!"PREPARATION_FAILED".equals(existing.getStatus())) return deferred ? existing : getCancellation(documentNumber, id);
      }
    } else if (body.previousReference() != null) {
      throw new ResponseStatusException(CONFLICT, "No existe la baja anterior indicada.");
    }
    boolean unreportedReceipt = original.getSeries().startsWith("B")
            && "REJECTED".equals(original.getStatus()) && Boolean.TRUE.equals(original.getXmlAvailable());
    if (!unreportedReceipt && !Set.of("ACCEPTED", "ACCEPTED_WITH_OBSERVATIONS").contains(original.getStatus()))
      throw new ResponseStatusException(CONFLICT, "La baja requiere un comprobante aceptado.");
    if (!Set.of("01", "03", "07", "08").contains(original.getDocumentType()))
      throw new ResponseStatusException(
              UNPROCESSABLE_CONTENT, "Este flujo anula facturas y boletas.");
    LocalDate deadlineBase = original.getIssueDate();
    if (!unreportedReceipt && LocalDate.now(clock).isBefore(LocalDate.of(2027, 1, 1))) {
      deadlineBase = Optional.ofNullable(original.getTaxResponse())
              .map(tax -> tax.getAcceptedAt() != null ? tax.getAcceptedAt() : tax.getAcceptanceDeadlineBase())
              .map(received -> received.atZoneSameInstant(clock.getZone()).toLocalDate())
              .orElse(original.getIssueDate());
    }
    if (LocalDate.now(clock).isAfter(deadlineBase.plusDays(7)))
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT,
              "La comunicación de baja está fuera del plazo de siete días calendario.");
    gateway.checkReady(documentNumber);
    Optional<DocumentData> claimed =
            store.claimCancellation(
                    auth.accountId(),
                    documentNumber,
                    id,
                    CancellationState.builder()
                            .id(UUID.randomUUID())
                            .documentId(id)
                            .method(method)
                            .previousReference(body.previousReference())
                            .reason(body.reason())
                            .status("PENDING")
                            .requestedAt(OffsetDateTime.now(clock))
                            .xmlAvailable(false)
                            .cdrAvailable(false)
                            .build());
    if (claimed.isEmpty()) return getCancellation(documentNumber, id);
    DocumentData document = claimed.orElseThrow();
    CancellationState cancellation = document.getCancellation();
    if (deferred) return cancellation;
    byte[] signed;
    try {
      LocalDate requested =
              cancellation.getRequestedAt().atZoneSameInstant(clock.getZone()).toLocalDate();
      signed =
              "RECEIPT_VOID".equals(method)
                      ? gateway.signedSummary(
                      document,
                      companies.getCompany(documentNumber),
                      cancellation.getReference(),
                      requested,
                      "3")
                      : gateway.signedVoid(
                      document,
                      companies.getCompany(documentNumber),
                      cancellation.getReference(),
                      requested,
                      body.reason());
    } catch (RuntimeException exception) {
      cancellation.setStatus("PREPARATION_FAILED");
      cancellation.setSunatDescription("Falló la preparación local; no se envió la baja.");
      store.saveDocument(auth.accountId(), document);
      throw exception;
    }
    cancellation.setXmlAvailable(true);
    cancellation.setLastAttemptAt(OffsetDateTime.now(clock));
    cancellation.setStatus("UNKNOWN");
    store.saveWithArtifact(auth.accountId(), document, "CANCELLATION_XML", signed);
    try {
      cancellation.setTicket(
              gateway.sendSummary(documentNumber, cancellation.getReference(), signed));
      cancellation.setStatus("SUBMITTED");
      store.saveDocument(auth.accountId(), document);
      return cancellation;
    } catch (ResponseStatusException exception) {
      if (exception instanceof SunatFaultException fault) {
        cancellation.setStatus(fault.documentStatus());
        cancellation.setSunatCode(fault.code());
      }
      cancellation.setSunatDescription(exception.getMessage());
      store.saveDocument(auth.accountId(), document);
      throw exception;
    }
  }

  @Override
  public CancellationState getCancellation(String documentNumber, UUID id) {
    DocumentData document = documents.getDocument(documentNumber, id);
    checkEnvironment(document);
    CancellationState cancellation =
            Optional.ofNullable(document.getCancellation())
                    .orElseThrow(
                            () ->
                                    new ResponseStatusException(
                                            NOT_FOUND, "Este comprobante no tiene solicitud de anulación."));
    if (Set.of("ACCEPTED", "REJECTED").contains(cancellation.getStatus())) return cancellation;
    if (!Set.of("SUBMITTED", "UNKNOWN", "MANUAL_REVIEW").contains(cancellation.getStatus())
            || cancellation.getTicket() == null || cancellation.getTicket().isBlank()) return cancellation;
    SunatStatus result = gateway.getSummaryStatus(documentNumber, cancellation.getTicket(), cancellation.getReference());
    if (result.hasConsistentSummaryReceipt()) return applyReceipt(document, result.receipt().orElseThrow());
    cancellation.setSunatCode(result.code());
    cancellation.setStatus(result.summaryProcessing() ? "SUBMITTED"
            : result.summaryRequiresReview() ? "MANUAL_REVIEW" : "UNKNOWN");
    cancellation.setSunatDescription(result.summaryProcessing() ? "SUNAT sigue procesando la baja."
            : "No hay una constancia coherente; la baja no está confirmada.");
    cancellation.setNextRetryAt(result.summaryRequiresReview() ? null : OffsetDateTime.now(clock).plusSeconds(30));
    store.saveDocument(auth.accountId(), document);
    return cancellation;
  }

  @Override
  public CancellationState recoverCancellation(String documentNumber, UUID id, String ticket) {
    DocumentData document = store.findDocument(auth.accountId(), documentNumber, id);
    checkEnvironment(document);
    CancellationState cancellation = Optional.ofNullable(document.getCancellation())
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "No existe solicitud de baja."));
    if (Set.of("ACCEPTED", "REJECTED").contains(cancellation.getStatus())) return cancellation;
    if (ticket != null && !ticket.isBlank()) {
      SunatStatus result = gateway.getSummaryStatus(documentNumber, ticket, cancellation.getReference());
      if (!result.hasConsistentSummaryReceipt())
        throw new ResponseStatusException(CONFLICT,
                "El ticket aún no tiene una CDR coherente que permita verificar su referencia.");
      SunatSendResult receipt = result.receipt().orElseThrow();
      cancellation.setTicket(ticket);
      return applyReceipt(document, receipt);
    }
    if (cancellation.getTicket() != null) return getCancellation(documentNumber, id);
    if ("PREPARATION_FAILED".equals(cancellation.getStatus()))
      return requestCancellation(documentNumber, id, new CancellationDetails(cancellation.getReason(),
              "RECEIPT_VOID".equals(cancellation.getMethod()) ? "DAILY_SUMMARY_VOID" : "COMMUNICATION_BAJA", true,
              cancellation.getPreviousReference()));
    Optional<DocumentData> claimed = store.claimRecovery(auth.accountId(), documentNumber, id, true);
    if (claimed.isEmpty()) return cancellation;
    document = claimed.orElseThrow();
    cancellation = document.getCancellation();
    if (!Boolean.TRUE.equals(cancellation.getXmlAvailable())) {
      cancellation.setStatus("PREPARATION_FAILED");
      store.saveDocument(auth.accountId(), document);
      return cancellation;
    }
    LocalDate base = document.getIssueDate();
    if (LocalDate.now(clock).isBefore(LocalDate.of(2027, 1, 1)))
      base = Optional.ofNullable(document.getTaxResponse()).map(t -> t.getAcceptedAt() != null ? t.getAcceptedAt() : t.getAcceptanceDeadlineBase())
              .map(t -> t.atZoneSameInstant(clock.getZone()).toLocalDate()).orElse(base);
    if (LocalDate.now(clock).isAfter(base.plusDays(7)))
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Plazo de baja vencido; recupera su CDR original.");
    byte[] signed = documents.downloadArtifact(documentNumber, id, "CANCELLATION_XML");
    cancellation.setStatus("UNKNOWN");
    store.saveDocument(auth.accountId(), document);
    try {
      cancellation.setTicket(gateway.sendSummary(documentNumber, cancellation.getReference(), signed));
      cancellation.setStatus("SUBMITTED");
      store.saveDocument(auth.accountId(), document);
      return cancellation;
    } catch (ResponseStatusException exception) {
      if (exception instanceof SunatFaultException fault) {
        cancellation.setStatus(fault.documentStatus());
        cancellation.setSunatCode(fault.code());
      }
      cancellation.setSunatDescription(exception.getReason());
      store.saveDocument(auth.accountId(), document);
      throw exception;
    }
  }

  private CancellationState applyReceipt(DocumentData document, SunatSendResult receipt) {
    CancellationState cancellation = document.getCancellation();
    cancellation.setStatus(receipt.accepted() ? "ACCEPTED" : "REJECTED");
    cancellation.setSunatCode(receipt.code());
    cancellation.setSunatDescription(receipt.description());
    cancellation.setCdrAvailable(true);
    cancellation.setCompletedAt(OffsetDateTime.now(clock));
    cancellation.setNextRetryAt(null);
    if (receipt.accepted()) document.setStatus("CANCELLED");
    store.saveWithArtifact(auth.accountId(), document, "CANCELLATION_CDR", receipt.cdrZip());
    return cancellation;
  }

  private void checkEnvironment(DocumentData document) {
    if (!Objects.equals(document.getEnvironment(), sunatProperties.environment()))
      throw new ResponseStatusException(CONFLICT, "El documento pertenece a otro entorno de emisión.");
  }

}
