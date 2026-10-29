package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.service.cancellation.models.CancellationDetails;
import pe.invoicing.app.service.document.models.DocumentData;
import pe.invoicing.app.service.document.models.SunatSendResult;
import pe.invoicing.app.service.document.models.SunatStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class CancellationServiceTest extends AbstractServiceTest {
  @Test
  @DisplayName("Baja de factura")
  void whenInvoiceCancelledThenSignRaAndStoreTicket() {
    // La baja usa RA firmada y permanece pendiente hasta el CDR.
    DocumentData document = issue("invoices/cash/request.json", "cancel-invoice");
    assertThat(
            cancellations
                    .requestCancellation(
                            COMPANY,
                            document.getId(),
                            fixture("cancellations/invoice/request.json", CancellationDetails.class))
                    .getStatus())
            .isEqualTo("SUBMITTED");
    assertThat(files.get(document.getId() + ":CANCELLATION_XML")).isNotEmpty();
  }

  @Test
  @DisplayName("Baja aceptada")
  void whenVoidCdrAcceptedThenCancelOriginal() {
    // Solo la aceptación de la baja anula el comprobante original.
    DocumentData document = issue("invoices/cash/request.json", "cancel-accepted");
    cancellations.requestCancellation(
            COMPANY,
            document.getId(),
            fixture("cancellations/invoice/request.json", CancellationDetails.class));
    doReturn(
            new SunatStatus(
                    "0", Optional.of(fixture("sunat/accepted/response.json", SunatSendResult.class))))
            .when(gateway)
            .getSummaryStatus(eq(COMPANY), eq("ticket-1"), anyString());
    assertThat(cancellations.getCancellation(COMPANY, document.getId()).getStatus())
            .isEqualTo("ACCEPTED");
    assertThat(document.getStatus()).isEqualTo("CANCELLED");
  }

  @Test
  @DisplayName("Baja rechazada")
  void whenVoidCdrRejectedThenKeepOriginalAccepted() {
    // Rechazar la baja no rechaza la venta original.
    DocumentData document = issue("invoices/cash/request.json", "cancel-rejected");
    cancellations.requestCancellation(
            COMPANY,
            document.getId(),
            fixture("cancellations/invoice/request.json", CancellationDetails.class));
    doReturn(
            new SunatStatus(
                    "0", Optional.of(fixture("sunat/rejected/response.json", SunatSendResult.class))))
            .when(gateway)
            .getSummaryStatus(eq(COMPANY), eq("ticket-1"), anyString());
    assertThat(cancellations.getCancellation(COMPANY, document.getId()).getStatus())
            .isEqualTo("REJECTED");
    assertThat(document.getStatus()).isEqualTo("ACCEPTED");
  }

  @Test
  @DisplayName("Baja idempotente")
  void whenCancellationRepeatedThenDoNotSubmitAgain() {
    // Consultar la solicitud previa evita un segundo envío.
    DocumentData document = issue("invoices/cash/request.json", "cancel-repeated");
    CancellationDetails request =
            fixture("cancellations/invoice/request.json", CancellationDetails.class);
    cancellations.requestCancellation(COMPANY, document.getId(), request);
    cancellations.requestCancellation(COMPANY, document.getId(), request);
    verify(gateway, times(1)).sendSummary(anyString(), anyString(), any());
  }

  @Test
  @DisplayName("Procedimiento incorrecto")
  void whenInvoiceUsesReceiptProcedureThenReject() {
    // Una factura no se anula mediante el resumen de boletas.
    DocumentData document = issue("invoices/cash/request.json", "cancel-invalid");
    assertThatThrownBy(
            () ->
                    cancellations.requestCancellation(
                            COMPANY,
                            document.getId(),
                            fixture("cancellations/receipt/request.json", CancellationDetails.class)))
            .isInstanceOf(ResponseStatusException.class);
    verify(store, never()).claimCancellation(any(), anyString(), any(), any());
  }

  @ParameterizedTest(name = "Estado {0}")
  @ValueSource(
          strings = {"REJECTED", "UNKNOWN", "DRAFT", "PREPARING", "PREPARATION_FAILED", "CANCELLED"})
  @DisplayName("Baja de estado inválido")
  void whenOriginalNotAcceptedThenRejectCancellation(String status) {
    // Un CDR previo no autoriza una transición desde cualquier estado.
    DocumentData document = issue("invoices/cash/request.json", "cancel-state");
    document.setStatus(status);
    assertThatThrownBy(
            () ->
                    cancellations.requestCancellation(
                            COMPANY,
                            document.getId(),
                            fixture("cancellations/invoice/request.json", CancellationDetails.class)))
            .isInstanceOf(ResponseStatusException.class);
    verify(store, never()).claimCancellation(any(), anyString(), any(), any());
  }

  @Test
  @DisplayName("Baja de nota de crédito")
  void whenInvoiceCreditNoteCancelledThenRaContainsCreditNoteType() throws Exception {
    // La RA debe identificar la nota que se anula, no una factura con su misma numeración.
    DocumentData invoice = issue("invoices/cash/request.json", "void-note-original");
    DocumentData note = saved.get(notes.issue(COMPANY, invoice.getId(), "void-note",
            new pe.invoicing.app.service.creditnote.models.CreditNoteDraft(
                    "06", "Devolución total", "FC01", invoice.getIssueDate(), null)).getDocumentId());
    cancellations.requestCancellation(COMPANY, note.getId(),
            fixture("cancellations/invoice/request.json", CancellationDetails.class));
    var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    var xml = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
            files.get(note.getId() + ":CANCELLATION_XML")));
    assertThat(xml.getElementsByTagNameNS("*", "DocumentTypeCode").item(0).getTextContent())
            .isEqualTo("07");
    assertThat(xml.getElementsByTagNameNS("*", "DocumentSerialID").item(0).getTextContent())
            .isEqualTo("FC01");
    assertThat(xml.getElementsByTagNameNS("*", "DocumentNumberID").item(0).getTextContent())
            .isEqualTo(note.getNumber().toString());
  }

  @Test
  @DisplayName("Documento otorgado")
  void whenDocumentWasGrantedThenRejectVoid() {
    // La declaración del emisor debe confirmar que el documento no fue otorgado.
    DocumentData document = issue("invoices/cash/request.json", "granted-invoice");
    assertThatThrownBy(() -> cancellations.requestCancellation(COMPANY, document.getId(),
            new CancellationDetails("Venta entregada", "COMMUNICATION_BAJA", false)))
            .isInstanceOf(ResponseStatusException.class);
    verify(gateway, never()).sendSummary(anyString(), anyString(), any());
  }

  @Test
  @DisplayName("Plazo de boleta aceptada")
  void whenReceiptAcceptedRecentlyThenUseCdrReceiptDateForVoid() {
    // En 2026 el plazo de baja de una boleta informada parte de su CDR aceptada.
    DocumentData document = issue("receipts/cash/request.json", "receipt-void-date");
    document.setStatus("ACCEPTED");
    document.setIssueDate(java.time.LocalDate.now(CLOCK).minusDays(9));
    document.setTaxResponse(pe.invoicing.app.service.document.models.TaxResult.builder()
            .acceptedAt(java.time.OffsetDateTime.now(CLOCK).minusDays(3)).build());
    assertThat(cancellations.requestCancellation(COMPANY, document.getId(),
            fixture("cancellations/receipt/request.json", CancellationDetails.class)).getStatus())
            .isEqualTo("SUBMITTED");
  }

  @Test
  @DisplayName("Baja de boleta no informada")
  void whenReceiptSummaryRejectedAndNotGrantedThenAllowVoidWithinGenerationDeadline() {
    // Una boleta firmada aún no informada puede incluirse como anulada en el RC.
    DocumentData document = issue("receipts/cash/request.json", "unreported-void");
    document.setStatus("REJECTED");
    document.getCommunication().setStatus("REJECTED");
    assertThat(cancellations.requestCancellation(COMPANY, document.getId(),
            fixture("cancellations/receipt/request.json", CancellationDetails.class)).getStatus())
            .isEqualTo("SUBMITTED");
  }
}
