package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.config.sunat.properties.SunatProperties;
import pe.invoicing.app.service.company.models.CertificateBundle;
import pe.invoicing.app.service.document.impl.DocumentPdfRenderer;
import pe.invoicing.app.service.document.impl.DocumentServiceImpl;
import pe.invoicing.app.service.document.models.DocumentData;
import pe.invoicing.app.service.document.models.DocumentDraft;
import pe.invoicing.app.service.document.models.Installment;
import pe.invoicing.app.service.document.models.IssueOperation;
import pe.invoicing.app.service.document.models.Payment;
import pe.invoicing.app.service.document.models.SunatSendResult;
import pe.invoicing.app.service.document.models.SunatStatus;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentServiceTest extends AbstractServiceTest {
  @Test
  @DisplayName("Factura con IGV")
  void whenCashInvoiceThenPersistSignedXmlAndAcceptedCdr() {
    // La aceptación procede del CDR, no del mero envío.
    DocumentData result = issue("invoices/cash/request.json", "invoice-001");
    assertThat(result.getStatus()).isEqualTo("ACCEPTED");
    assertThat(result.getTotals().getTotalAmount()).isEqualTo("118.00");
    assertThat(result.getTotals().getIgvAmount()).isEqualTo("18.00");
    assertThat(files.get(result.getId() + ":XML")).isNotEmpty();
    assertThat(files.get(result.getId() + ":CDR")).isNotEmpty();
    verify(gateway).sendBill(eq(result), any());
  }

  @ParameterizedTest(name = "CDR {0} → {1}")
  @CsvSource({"accepted,ACCEPTED", "observed,ACCEPTED_WITH_OBSERVATIONS", "rejected,REJECTED"})
  @DisplayName("Resultado del CDR")
  void whenCdrReceivedThenPreserveFiscalOutcome(String fixture, String status) {
    // Un rechazo nunca se transforma en aceptación.
    doReturn(fixture("sunat/" + fixture + "/response.json", SunatSendResult.class))
            .when(gateway)
            .sendBill(any(), any());
    assertThat(issue("invoices/cash/request.json", "invoice-cdr").getStatus()).isEqualTo(status);
  }

  @Test
  @DisplayName("Cuotas válidas")
  void whenInstallmentsMatchThenIssueCreditInvoice() {
    // El mapper real conserva saldo y vencimientos del request.
    assertThat(
            issue("invoices/installments/request.json", "invoice-credit")
                    .getPayment()
                    .getInstallments())
            .hasSize(2);
  }

  @ParameterizedTest(name = "Cuota {0}")
  @ValueSource(strings = {"-1.00", "0.00", "117.00"})
  @DisplayName("Cuotas inválidas")
  void whenInstallmentInvalidThenRejectBeforePersistence(String amount) {
    // Se valida en servicio incluso si otro adaptador omite Bean Validation.
    DocumentDraft request =
            draft("invoices/cash/request.json").toBuilder()
                    .payment(
                            Payment.builder()
                                    .type("CREDIT")
                                    .pendingAmount("118.00")
                                    .installments(
                                            List.of(
                                                    Installment.builder()
                                                            .amount(amount)
                                                            .dueDate(LocalDate.of(2026, 11, 26))
                                                            .build(),
                                                    Installment.builder()
                                                            .amount("118.00")
                                                            .dueDate(LocalDate.of(2026, 12, 26))
                                                            .build()))
                                    .build())
                    .build();
    assertThatThrownBy(() -> documents.issueNewDocument(COMPANY, "invalid-credit", request))
            .isInstanceOf(ResponseStatusException.class);
    verify(store, never()).createIssuance(any(), any(), anyString(), anyString());
  }

  @Test
  @DisplayName("Cuota cero compensada")
  void whenZeroInstallmentAndMatchingSumThenReject() {
    // La suma correcta no vuelve válida una cuota de importe cero.
    DocumentDraft request =
            draft("invoices/cash/request.json").toBuilder()
                    .payment(
                            Payment.builder()
                                    .type("CREDIT")
                                    .pendingAmount("118.00")
                                    .installments(
                                            List.of(
                                                    Installment.builder()
                                                            .amount("0.00")
                                                            .dueDate(LocalDate.of(2026, 11, 26))
                                                            .build(),
                                                    Installment.builder()
                                                            .amount("118.00")
                                                            .dueDate(LocalDate.of(2026, 12, 26))
                                                            .build()))
                                    .build())
                    .build();
    assertThatThrownBy(() -> documents.issueNewDocument(COMPANY, "zero-part", request))
            .isInstanceOf(ResponseStatusException.class);
  }

  @ParameterizedTest(name = "Fecha {0}")
  @ValueSource(strings = {"2026-10-27", "2026-10-22"})
  @DisplayName("Fecha fuera de plazo")
  void whenInvoiceDateOutOfRangeThenReject(String date) {
    // No se consume numeración para fechas inválidas.
    assertThatThrownBy(
            () ->
                    documents.issueNewDocument(
                            COMPANY,
                            "date-error",
                            draft("invoices/cash/request.json").toBuilder()
                                    .issueDate(LocalDate.parse(date))
                                    .build()))
            .isInstanceOf(ResponseStatusException.class);
    verify(store, never()).createIssuance(any(), any(), anyString(), anyString());
  }

  @Test
  @DisplayName("Boleta pendiente")
  void whenReceiptSubmittedThenAwaitTicketResult() {
    // El ticket solo confirma recepción del resumen.
    DocumentData result = issue("receipts/cash/request.json", "receipt-001");
    assertThat(result.getStatus()).isEqualTo("SUBMITTED");
    assertThat(result.getCommunication().getTicket()).isEqualTo("ticket-1");
    verify(gateway, never()).sendBill(any(), any());
    assertThat(files.get(result.getId() + ":SUMMARY_XML")).isNotEmpty();
  }

  @Test
  @DisplayName("Boleta aceptada")
  void whenTicketReturnsCdrThenAcceptReceipt() {
    // La consulta del ticket actualiza el mismo comprobante.
    DocumentData result = issue("receipts/cash/request.json", "receipt-002");
    doReturn(
            new SunatStatus(
                    "0", Optional.of(fixture("sunat/accepted/response.json", SunatSendResult.class))))
            .when(gateway)
            .getSummaryStatus(eq(COMPANY), eq("ticket-1"), anyString());
    assertThat(documents.getDocument(COMPANY, result.getId()).getStatus()).isEqualTo("ACCEPTED");
  }

  @Test
  @DisplayName("Envío incierto")
  void whenNetworkTimesOutThenKeepXmlAndUnknownStatus() {
    // Un timeout no demuestra rechazo ni autoriza otro envío.
    doThrow(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT))
            .when(gateway)
            .sendBill(any(), any());
    assertThatThrownBy(() -> issue("invoices/cash/request.json", "timeout-001"))
            .isInstanceOf(ResponseStatusException.class);
    assertThat(saved.values())
            .singleElement()
            .satisfies(
                    d -> {
                      assertThat(d.getStatus()).isEqualTo("UNKNOWN");
                      assertThat(files.get(d.getId() + ":XML")).isNotEmpty();
                    });
  }

  @Test
  @DisplayName("Conciliar factura")
  void whenUnknownInvoiceIsFoundThenApplyCdrWithoutResending() {
    // La recuperación consulta SUNAT usando la numeración original.
    DocumentData result = issue("invoices/cash/request.json", "reconcile-001");
    result.setStatus("UNKNOWN");
    doReturn(
            new SunatStatus(
                    "0", Optional.of(fixture("sunat/accepted/response.json", SunatSendResult.class))))
            .when(gateway)
            .consultBill(result);
    assertThat(documents.getDocument(COMPANY, result.getId()).getStatus()).isEqualTo("ACCEPTED");
    verify(gateway, times(1)).sendBill(any(), any());
  }

  @Test
  @DisplayName("Reintento idempotente")
  void whenSameKeyAndBodyThenReturnOriginalWithoutSending() {
    // El resultado original se reutiliza con la misma clave y cuerpo.
    DocumentDraft request = draft("invoices/cash/request.json");
    DocumentData result = issue("invoices/cash/request.json", "same-key-1");
    when(artifacts.findIssueOperation(ACCOUNT, COMPANY, crypto.sha256("same-key-1")))
            .thenReturn(
                    Optional.of(
                            new IssueOperation(
                                    crypto.sha256(JSON.valueToTree(request).toString()), result.getId())));
    assertThat(documents.issueNewDocument(COMPANY, "same-key-1", request).getDocumentId())
            .isEqualTo(result.getId());
    verify(gateway, times(1)).sendBill(any(), any());
  }

  @Test
  @DisplayName("Clave con otro cuerpo")
  void whenSameKeyHasDifferentBodyThenRejectConflict() {
    // Una clave no puede reutilizarse para otra venta.
    when(artifacts.findIssueOperation(ACCOUNT, COMPANY, crypto.sha256("same-key-2")))
            .thenReturn(Optional.of(new IssueOperation("different-hash", UUID.randomUUID())));
    assertThatThrownBy(() -> issue("invoices/cash/request.json", "same-key-2"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(
                    e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
    verify(gateway, never()).sendBill(any(), any());
  }

  @Test
  @DisplayName("Reintentar preparación")
  void whenPreparationFailedThenRetrySameDocument() {
    // Fallar antes del envío permite reintentar sin consumir otro correlativo.
    DocumentDraft request = draft("invoices/cash/request.json");
    Optional<String> validCertificate = companyStore.findEncryptedCertificate(ACCOUNT, COMPANY);
    when(companyStore.findEncryptedCertificate(ACCOUNT, COMPANY))
            .thenReturn(
                    validCertificate,
                    validCertificate,
                    Optional.of(
                            vault.encrypt(
                                    new CertificateBundle("invalid-certificate", "password").serialize())));
    assertThatThrownBy(() -> documents.issueNewDocument(COMPANY, "prepare-retry", request))
            .isInstanceOf(ResponseStatusException.class);
    DocumentData result = saved.values().iterator().next();
    when(artifacts.findIssueOperation(ACCOUNT, COMPANY, crypto.sha256("prepare-retry")))
            .thenReturn(
                    Optional.of(
                            new IssueOperation(
                                    crypto.sha256(JSON.valueToTree(request).toString()), result.getId())));
    when(companyStore.findEncryptedCertificate(ACCOUNT, COMPANY)).thenReturn(validCertificate);
    documents.issueNewDocument(COMPANY, "prepare-retry", request);
    assertThat(result.getStatus()).isEqualTo("ACCEPTED");
    verify(store, times(1)).createIssuance(any(), any(), anyString(), anyString());
    verify(gateway, times(1)).sendBill(any(), any());
  }

  @ParameterizedTest(name = "Formato {0}")
  @ValueSource(strings = {"A4", "TICKET_48", "TICKET_50", "TICKET_55", "TICKET_58", "TICKET_80"})
  @DisplayName("PDF del comprobante")
  void whenPdfRequestedThenRenderActualDocument(String format) {
    // Se usa PDFBox real y el XML firmado del comprobante.
    DocumentData result = issue("invoices/cash/request.json", "pdf-invoice");
    org.mockito.Mockito.clearInvocations(artifacts); // Medir sólo la descarga, no la preparación de emisión.
    assertThat(documents.downloadPdf(COMPANY, result.getId(), format))
            .startsWith("%PDF-".getBytes());
    verify(artifacts).findArtifact(ACCOUNT, COMPANY, result.getId(), "XML");
  }

  @Test
  @DisplayName("Reintento fuera de plazo")
  void whenPreparationRetryIsLateThenRejectWithoutResending() {
    // Una preparación pendiente no habilita enviar una factura que ya venció.
    DocumentDraft request = draft("invoices/cash/request.json");
    DocumentData original = issue("invoices/cash/request.json", "late-original");
    original.setStatus("PREPARATION_FAILED");
    when(artifacts.findIssueOperation(ACCOUNT, COMPANY, crypto.sha256("late-original")))
            .thenReturn(
                    Optional.of(
                            new IssueOperation(
                                    crypto.sha256(JSON.valueToTree(request).toString()), original.getId())));
    DocumentServiceImpl later =
            new DocumentServiceImpl(
                    crypto,
                    Clock.offset(CLOCK, Duration.ofDays(4)),
                    authentication,
                    companies,
                    store,
                    artifacts,
                    new DocumentPdfRenderer(),
                    gateway,
                    new SunatProperties(
                            URI.create("http://localhost/unused"),
                            URI.create("http://localhost/unused"),
                            new MockEnvironment()));
    assertThatThrownBy(() -> later.issueNewDocument(COMPANY, "late-original", request))
            .isInstanceOf(ResponseStatusException.class);
    verify(gateway, times(1)).sendBill(any(), any());
  }

  @Test
  @DisplayName("Recepción histórica del CDR")
  void whenCdrRecoveredThenPreserveOriginalAcceptanceDate() {
    // Recuperar una constancia no reinicia el plazo tributario para comunicar la baja.
    DocumentData document = issue("invoices/cash/request.json", "historical-cdr");
    var received = java.time.OffsetDateTime.now(CLOCK).minusDays(8);
    document.getTaxResponse().setAcceptedAt(received);
    document.setStatus("UNKNOWN");
    doReturn(new SunatStatus("0", Optional.of(
            fixture("sunat/accepted/response.json", SunatSendResult.class))))
            .when(gateway).consultBill(document);
    assertThat(documents.getDocument(COMPANY, document.getId()).getTaxResponse().getAcceptedAt())
            .isEqualTo(received);
  }

  @Test
  @DisplayName("Recuperación sin fecha conocida")
  void whenRecoveredCdrHasNoReceiptDateThenDoNotUseRetrievalTime() {
    // Sin evidencia histórica se conserva una fecha conservadora del envío original.
    DocumentData document = issue("invoices/cash/request.json", "unknown-receipt-date");
    var sent = java.time.OffsetDateTime.now(CLOCK).minusDays(8);
    document.getCommunication().setLastAttemptAt(sent);
    document.getTaxResponse().setAcceptedAt(null);
    document.setStatus("UNKNOWN");
    doReturn(new SunatStatus("0", Optional.of(
            fixture("sunat/accepted/response.json", SunatSendResult.class))))
            .when(gateway).consultBill(document);
    assertThat(documents.getDocument(COMPANY, document.getId()).getTaxResponse().getAcceptedAt()).isNull();
    assertThat(document.getTaxResponse().getAcceptanceDeadlineBase()).isEqualTo(sent);
  }

  @Test
  @DisplayName("Crédito con pago inicial")
  void whenCreditHasInitialPaymentThenIssueWithRealPendingAmount() {
    // Una venta de 118 con 18 pagados al emitir tiene un saldo y cuotas de 100.
    DocumentDraft request = draft("invoices/cash/request.json").toBuilder()
            .payment(Payment.builder().type("CREDIT").pendingAmount("100.00")
                    .installments(List.of(Installment.builder().amount("100.00")
                            .dueDate(LocalDate.of(2026, 11, 26)).build())).build()).build();
    DocumentData result = saved.get(documents.issueNewDocument(COMPANY, "partial-credit", request)
            .getDocumentId());
    assertThat(result.getTotals().getTotalAmount()).isEqualTo("118.00");
    assertThat(result.getPayment().getPendingAmount()).isEqualTo("100.00");
    assertThat(new String(files.get(result.getId() + ":XML"), java.nio.charset.StandardCharsets.UTF_8))
            .contains(">Credito<", ">100.00<", ">118.00<");
  }

  @Test
  @DisplayName("Saldo superior a venta")
  void whenPendingAmountExceedsTotalThenReject() {
    // Un pago parcial no permite financiar más que el importe de la operación.
    DocumentDraft request = draft("invoices/cash/request.json").toBuilder()
            .payment(Payment.builder().type("CREDIT").pendingAmount("119.00")
                    .installments(List.of(Installment.builder().amount("119.00")
                            .dueDate(LocalDate.of(2026, 11, 26)).build())).build()).build();
    assertThatThrownBy(() -> documents.issueNewDocument(COMPANY, "excess-credit", request))
            .isInstanceOf(ResponseStatusException.class);
    verify(gateway, never()).sendBill(any(), any());
  }

  @Test
  @DisplayName("Fechas de emisión inmutables")
  void whenCdrRecoveredThenKeepOriginalIssueAndReportTimestamps() {
    // La auditoría de una consulta tardía se registra aparte de la emisión original.
    DocumentData document = issue("invoices/cash/request.json", "immutable-dates");
    var original = java.time.OffsetDateTime.now(CLOCK).minusDays(5);
    document.setIssuedAt(original);
    document.setReportedAt(original);
    document.setStatus("UNKNOWN");
    doReturn(new SunatStatus("0", Optional.of(fixture("sunat/accepted/response.json", SunatSendResult.class))))
            .when(gateway).consultBill(document);
    documents.getDocument(COMPANY, document.getId());
    assertThat(document.getIssuedAt()).isEqualTo(original);
    assertThat(document.getReportedAt()).isEqualTo(original);
  }

  @Test
  @DisplayName("Repetir UNKNOWN sin consultar ni reenviar")
  void whenUnknownIdempotentRetryThenReturnStoredDocumentWithoutNetwork() {
    var request = draft("invoices/cash/request.json");
    var result = issue("invoices/cash/request.json", "unknown-repeat");
    result.setStatus("UNKNOWN");
    when(artifacts.findIssueOperation(ACCOUNT, COMPANY, crypto.sha256("unknown-repeat")))
            .thenReturn(Optional.of(new IssueOperation(crypto.sha256(JSON.valueToTree(request).toString()), result.getId())));
    assertThat(documents.issueNewDocument(COMPANY, "unknown-repeat", request).getDocumentId()).isEqualTo(result.getId());
    assertThat(result.getStatus()).isEqualTo("UNKNOWN");
    verify(gateway, never()).consultBill(any());
    verify(gateway, times(1)).sendBill(any(), any());
  }

  @Test
  @DisplayName("Consultar UNKNOWN local en beta")
  void whenReadingUnknownInBetaThenReturnStoredStateWithoutUnsupportedConsultation() {
    var result = issue("invoices/cash/request.json", "beta-read-unknown");
    result.setStatus("UNKNOWN");
    var endpoint = java.net.URI.create("https://e-beta.sunat.gob.pe/ol-ti-itcpfegem-beta/billService");
    var service = new pe.invoicing.app.service.document.impl.DocumentServiceImpl(
            crypto, CLOCK, authentication, companies, store, artifacts,
            new pe.invoicing.app.service.document.impl.DocumentPdfRenderer(), gateway,
            new pe.invoicing.app.config.sunat.properties.SunatProperties(endpoint, endpoint, new org.springframework.mock.env.MockEnvironment()));
    assertThat(service.getDocument(COMPANY, result.getId()).getStatus()).isEqualTo("UNKNOWN");
    verify(gateway, never()).consultBill(any());
    verify(gateway, times(1)).sendBill(any(), any());
  }

}
